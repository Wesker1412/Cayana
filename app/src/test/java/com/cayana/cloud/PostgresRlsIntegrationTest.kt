package com.cayana.cloud

import com.cayana.cloud.crypto.CloudCryptoService
import com.cayana.cloud.crypto.CloudDecryptionException
import com.cayana.cloud.crypto.EncryptedCloudRecord
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PostgresRlsIntegrationTest {

    companion object {
        private var pg: EmbeddedPostgres? = null
        private var connection: Connection? = null

        @BeforeClass
        @JvmStatic
        fun setUpDatabase() {
            try {
                pg = EmbeddedPostgres.start()
                val conn = pg!!.postgresDatabase.connection
                connection = conn

                val statement = conn.createStatement()

                // Create mock Supabase auth schema and auth.uid() helper
                statement.execute("CREATE SCHEMA IF NOT EXISTS auth;")
                statement.execute(
                    """
                    CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid AS ${'$'}${'$'}
                        SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
                    ${'$'}${'$'} LANGUAGE sql STABLE;
                    """.trimIndent()
                )

                // Create standard unprivileged Supabase roles 'authenticated' and 'anon'
                statement.execute("DO ${'$'}${'$'} BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated; END IF; END ${'$'}${'$'};")
                statement.execute("DO ${'$'}${'$'} BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN CREATE ROLE anon; END IF; END ${'$'}${'$'};")

                // Allow unprivileged roles to execute auth.uid()
                statement.execute("GRANT USAGE ON SCHEMA auth TO authenticated, anon;")
                statement.execute("GRANT EXECUTE ON FUNCTION auth.uid() TO authenticated, anon;")

                // Apply production migration script directly
                val migrationFile = File("supabase/migrations/20261008000000_cloud_memory_records.sql")
                val migrationSql = if (migrationFile.exists()) {
                    migrationFile.readText()
                } else {
                    File("../supabase/migrations/20261008000000_cloud_memory_records.sql").readText()
                }

                statement.execute(migrationSql)

                // Strictly NO test-side "GRANT ALL PRIVILEGES ...".
                // Roles must operate exclusively with privileges granted by the production migration!
            } catch (e: Throwable) {
                println("Failed to start Embedded Postgres: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDownDatabase() {
            connection?.close()
            pg?.close()
        }
    }

    @Before
    fun cleanTable() {
        val conn = connection ?: return
        val stmt = conn.createStatement()
        stmt.execute("RESET ROLE;")
        stmt.execute("TRUNCATE TABLE cloud_memory_records;")
    }

    private fun setCallerTenant(tenantId: UUID?, conn: Connection = connection!!) {
        val stmt = conn.createStatement()
        stmt.execute("RESET ROLE;")
        if (tenantId != null) {
            stmt.execute("SET request.jwt.claim.sub = '$tenantId';")
        } else {
            stmt.execute("SET request.jwt.claim.sub = '';")
        }
        stmt.execute("SET ROLE authenticated;")
    }

    private fun setCallerAnon(conn: Connection = connection!!) {
        val stmt = conn.createStatement()
        stmt.execute("RESET ROLE;")
        stmt.execute("SET request.jwt.claim.sub = '';")
        stmt.execute("SET ROLE anon;")
    }

    private val tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val validNonce = "AQIDBAUGBwgJCgsM" // exactly 12 bytes decoded

    @Test
    fun productionMigrationAllowsAuthenticatedRpc() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        val rpcStmt = conn.prepareStatement("SELECT (upsert_cloud_memory_record(?, ?, ?, ?, ?, ?))->>'status'")
        rpcStmt.setString(1, "mem-auth-rpc-test")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, validNonce)
        rpcStmt.setString(5, "Y2lwaGVydGV4dA==")
        rpcStmt.setBoolean(6, false)

        val rs = rpcStmt.executeQuery()
        assertTrue(rs.next())
        assertEquals("ACCEPTED", rs.getString(1))
    }

    @Test
    fun productionMigrationAllowsAuthenticatedPull() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        // Insert a record
        val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcStmt.setString(1, "mem-pull-test")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, validNonce)
        rpcStmt.setString(5, "Y2lwaGVydGV4dA==")
        rpcStmt.setBoolean(6, false)
        rpcStmt.execute()

        // Pull records via SELECT on table with change_seq order
        val selectStmt = conn.prepareStatement(
            "SELECT memory_id, revision, ciphertext FROM cloud_memory_records WHERE change_seq > 0 ORDER BY change_seq ASC"
        )
        val rs = selectStmt.executeQuery()
        assertTrue(rs.next())
        assertEquals("mem-pull-test", rs.getString("memory_id"))
        assertEquals(1L, rs.getLong("revision"))
        assertEquals("Y2lwaGVydGV4dA==", rs.getString("ciphertext"))
    }

    @Test
    fun productionMigrationDoesNotExposeToAnon() {
        val conn = connection ?: throw IllegalStateException("Database not connected")

        // Switch to anon role
        setCallerAnon(conn)

        // 1. Anon calling RPC must fail with permission denied
        try {
            val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
            rpcStmt.setString(1, "mem-anon-test")
            rpcStmt.setLong(2, 1L)
            rpcStmt.setInt(3, 1)
            rpcStmt.setString(4, validNonce)
            rpcStmt.setString(5, "Y2lwaGVydGV4dA==")
            rpcStmt.setBoolean(6, false)
            rpcStmt.execute()
            fail("Anon must NOT be allowed to execute upsert_cloud_memory_record")
        } catch (e: SQLException) {
            assertTrue(
                "Expected permission denied, got: ${e.message}",
                e.message?.contains("permission denied") == true || e.message?.contains("Unauthorized") == true
            )
        }

        // 2. Anon selecting from table directly must fail with permission denied
        try {
            conn.createStatement().executeQuery("SELECT * FROM cloud_memory_records")
            fail("Anon must NOT have SELECT privilege on cloud_memory_records table")
        } catch (e: SQLException) {
            assertTrue(
                "Expected permission denied, got: ${e.message}",
                e.message?.contains("permission denied") == true
            )
        }
    }

    @Test
    fun tenantACannotReadTenantB() {
        val conn = connection ?: throw IllegalStateException("Database not connected")

        // 1. Tenant B inserts a record via RPC
        setCallerTenant(tenantB)
        val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcStmt.setString(1, "mem-tenant-b-secret")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, validNonce) // 12-byte base64 nonce
        rpcStmt.setString(5, "Y2lwaGVydGV4dEJfcmVjb3Jk")
        rpcStmt.setBoolean(6, false)
        rpcStmt.execute()

        // Verify Tenant B can read its own record
        val selectBStmt = conn.prepareStatement("SELECT count(*) FROM cloud_memory_records WHERE memory_id = ?")
        selectBStmt.setString(1, "mem-tenant-b-secret")
        val rsB = selectBStmt.executeQuery()
        assertTrue(rsB.next())
        assertEquals(1, rsB.getInt(1))

        // 2. Switch to Tenant A
        setCallerTenant(tenantA)

        // Tenant A tries to select Tenant B's record
        val selectAStmt = conn.prepareStatement("SELECT * FROM cloud_memory_records WHERE memory_id = ?")
        selectAStmt.setString(1, "mem-tenant-b-secret")
        val rsA = selectAStmt.executeQuery()
        assertFalse("Tenant A must NOT see Tenant B's record!", rsA.next())

        // Tenant A selects all rows
        val selectAllA = conn.createStatement().executeQuery("SELECT count(*) FROM cloud_memory_records")
        assertTrue(selectAllA.next())
        assertEquals("Tenant A must see 0 rows", 0, selectAllA.getInt(1))
    }

    @Test
    fun tenantACannotUpdateTenantB() {
        val conn = connection ?: throw IllegalStateException("Database not connected")

        // 1. Ensure Tenant B has a record
        setCallerTenant(tenantB)
        val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcStmt.setString(1, "mem-tenant-b-target")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, validNonce)
        rpcStmt.setString(5, "b3JpZ2luYWxfY2lwaGVydGV4dA==")
        rpcStmt.setBoolean(6, false)
        rpcStmt.execute()

        // 2. Switch to Tenant A and attempt direct SQL UPDATE
        setCallerTenant(tenantA)
        val updateStmt = conn.prepareStatement(
            "UPDATE cloud_memory_records SET ciphertext = ? WHERE memory_id = ?"
        )
        updateStmt.setString(1, "aGFja2VkX2NpcGhlcnRleHQ=")
        updateStmt.setString(2, "mem-tenant-b-target")
        val rowsAffected = updateStmt.executeUpdate()
        assertEquals("Tenant A update on Tenant B record must affect 0 rows", 0, rowsAffected)

        // 3. Tenant A attempts RPC upsert with Tenant B's memory_id
        val rpcA = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcA.setString(1, "mem-tenant-b-target")
        rpcA.setLong(2, 2L)
        rpcA.setInt(3, 1)
        rpcA.setString(4, validNonce)
        rpcA.setString(5, "YXR0YWNrZXJfY2lwaGVydGV4dA==")
        rpcA.setBoolean(6, false)
        rpcA.execute()

        // Verify Tenant B's record is completely untouched
        setCallerTenant(tenantB)
        val selectB = conn.prepareStatement("SELECT ciphertext, revision FROM cloud_memory_records WHERE memory_id = ?")
        selectB.setString(1, "mem-tenant-b-target")
        val rs = selectB.executeQuery()
        assertTrue(rs.next())
        assertEquals("b3JpZ2luYWxfY2lwaGVydGV4dA==", rs.getString("ciphertext"))
        assertEquals(1L, rs.getLong("revision"))
    }

    @Test
    fun anonymousUnauthenticatedRequestReturnsNoRows() {
        val conn = connection ?: throw IllegalStateException("Database not connected")

        // Tenant A creates a record
        setCallerTenant(tenantA)
        val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcStmt.setString(1, "mem-tenant-a-protected")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, validNonce)
        rpcStmt.setString(5, "cHJvdGVjdGVkX2NpcGhlcg==")
        rpcStmt.setBoolean(6, false)
        rpcStmt.execute()

        // Now set caller to null / unauthenticated
        setCallerTenant(null)

        // Direct SELECT returns 0 rows due to RLS
        val selectStmt = conn.createStatement().executeQuery("SELECT count(*) FROM cloud_memory_records")
        assertTrue(selectStmt.next())
        assertEquals("Unauthenticated query must return 0 rows due to RLS", 0, selectStmt.getInt(1))

        // RPC call must fail with Unauthorized error
        try {
            val rpcUnauth = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
            rpcUnauth.setString(1, "mem-fail")
            rpcUnauth.setLong(2, 1L)
            rpcUnauth.setInt(3, 1)
            rpcUnauth.setString(4, validNonce)
            rpcUnauth.setString(5, "Y2lwaGVy")
            rpcUnauth.setBoolean(6, false)
            rpcUnauth.execute()
            fail("Expected exception when calling RPC without authentication")
        } catch (e: SQLException) {
            assertTrue(
                "Expected Unauthorized exception, got: ${e.message}",
                e.message?.contains("Unauthorized") == true || e.message?.contains("auth.uid() is null") == true
            )
        }
    }

    @Test
    fun rpcIdempotencyAndStaleRejection() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        // 1. Initial insert rev 1 -> ACCEPTED
        val stmt1 = conn.prepareStatement("SELECT (upsert_cloud_memory_record(?, ?, ?, ?, ?, ?))->>'status'")
        stmt1.setString(1, "mem-idempotency-test")
        stmt1.setLong(2, 1L)
        stmt1.setInt(3, 1)
        stmt1.setString(4, validNonce)
        stmt1.setString(5, "Y2lwaGVyMQ==")
        stmt1.setBoolean(6, false)
        val rs1 = stmt1.executeQuery()
        assertTrue(rs1.next())
        assertEquals("ACCEPTED", rs1.getString(1))

        // 2. Retry rev 1 -> IDEMPOTENT
        val stmtRetry = conn.prepareStatement("SELECT (upsert_cloud_memory_record(?, ?, ?, ?, ?, ?))->>'status'")
        stmtRetry.setString(1, "mem-idempotency-test")
        stmtRetry.setLong(2, 1L)
        stmtRetry.setInt(3, 1)
        stmtRetry.setString(4, validNonce)
        stmtRetry.setString(5, "Y2lwaGVyMQ==")
        stmtRetry.setBoolean(6, false)
        val rsRetry = stmtRetry.executeQuery()
        assertTrue(rsRetry.next())
        assertEquals("IDEMPOTENT", rsRetry.getString(1))

        // 3. Update rev 2 -> ACCEPTED
        val stmt2 = conn.prepareStatement("SELECT (upsert_cloud_memory_record(?, ?, ?, ?, ?, ?))->>'status'")
        stmt2.setString(1, "mem-idempotency-test")
        stmt2.setLong(2, 2L)
        stmt2.setInt(3, 1)
        stmt2.setString(4, validNonce)
        stmt2.setString(5, "Y2lwaGVyMg==")
        stmt2.setBoolean(6, false)
        val rs2 = stmt2.executeQuery()
        assertTrue(rs2.next())
        assertEquals("ACCEPTED", rs2.getString(1))

        // 4. Stale rev 1 -> STALE
        val stmtStale = conn.prepareStatement("SELECT (upsert_cloud_memory_record(?, ?, ?, ?, ?, ?))->>'status'")
        stmtStale.setString(1, "mem-idempotency-test")
        stmtStale.setLong(2, 1L)
        stmtStale.setInt(3, 1)
        stmtStale.setString(4, validNonce)
        stmtStale.setString(5, "Y2lwaGVyX3N0YWxl")
        stmtStale.setBoolean(6, false)
        val rsStale = stmtStale.executeQuery()
        assertTrue(rsStale.next())
        assertEquals("STALE", rsStale.getString(1))
    }

    @Test
    fun concurrentRevision2And3AlwaysKeepsRevision3() {
        val executor = Executors.newFixedThreadPool(2)
        val iterations = 10

        for (i in 0 until iterations) {
            val memoryId = "mem-concurrent-race-$i"
            val conn1 = pg!!.postgresDatabase.connection
            val conn2 = pg!!.postgresDatabase.connection

            try {
                // Initial insert rev 1
                setCallerTenant(tenantA, conn1)
                val initStmt = conn1.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
                initStmt.setString(1, memoryId)
                initStmt.setLong(2, 1L)
                initStmt.setInt(3, 1)
                initStmt.setString(4, validNonce)
                initStmt.setString(5, "Y2lwaGVyX3JldjE=")
                initStmt.setBoolean(6, false)
                initStmt.execute()

                val latch = CountDownLatch(2)

                // Task 1: Upsert revision 2
                executor.submit {
                    try {
                        setCallerTenant(tenantA, conn1)
                        val stmt = conn1.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
                        stmt.setString(1, memoryId)
                        stmt.setLong(2, 2L)
                        stmt.setInt(3, 1)
                        stmt.setString(4, validNonce)
                        stmt.setString(5, "Y2lwaGVyX3JldjI=")
                        stmt.setBoolean(6, false)
                        stmt.execute()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }

                // Task 2: Upsert revision 3
                executor.submit {
                    try {
                        setCallerTenant(tenantA, conn2)
                        val stmt = conn2.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
                        stmt.setString(1, memoryId)
                        stmt.setLong(2, 3L)
                        stmt.setInt(3, 1)
                        stmt.setString(4, validNonce)
                        stmt.setString(5, "Y2lwaGVyX3JldjM=")
                        stmt.setBoolean(6, false)
                        stmt.execute()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }

                assertTrue(latch.await(5, TimeUnit.SECONDS))

                // Verify: Final record must have revision 3 and matching ciphertext, never overwritten by rev 2
                setCallerTenant(tenantA, conn1)
                val verifyStmt = conn1.prepareStatement(
                    "SELECT revision, ciphertext FROM cloud_memory_records WHERE memory_id = ?"
                )
                verifyStmt.setString(1, memoryId)
                val rs = verifyStmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("Iteration $i: Highest revision must always be 3", 3L, rs.getLong("revision"))
                assertEquals("Iteration $i: Stored ciphertext must match revision 3", "Y2lwaGVyX3JldjM=", rs.getString("ciphertext"))
                assertFalse("Exactly one row must exist", rs.next())
            } finally {
                conn1.close()
                conn2.close()
            }
        }
        executor.shutdown()
    }

    @Test
    fun concurrentInitialInsertSameMemoryIsSafe() {
        val executor = Executors.newFixedThreadPool(2)
        val iterations = 10

        for (i in 0 until iterations) {
            val memoryId = "mem-concurrent-init-$i"
            val conn1 = pg!!.postgresDatabase.connection
            val conn2 = pg!!.postgresDatabase.connection

            try {
                val latch = CountDownLatch(2)

                executor.submit {
                    try {
                        setCallerTenant(tenantA, conn1)
                        val stmt = conn1.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
                        stmt.setString(1, memoryId)
                        stmt.setLong(2, 1L)
                        stmt.setInt(3, 1)
                        stmt.setString(4, validNonce)
                        stmt.setString(5, "Y2lwaGVyX2luaXRfMQ==")
                        stmt.setBoolean(6, false)
                        stmt.execute()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }

                executor.submit {
                    try {
                        setCallerTenant(tenantA, conn2)
                        val stmt = conn2.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
                        stmt.setString(1, memoryId)
                        stmt.setLong(2, 1L)
                        stmt.setInt(3, 1)
                        stmt.setString(4, validNonce)
                        stmt.setString(5, "Y2lwaGVyX2luaXRfMg==")
                        stmt.setBoolean(6, false)
                        stmt.execute()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }

                assertTrue(latch.await(5, TimeUnit.SECONDS))

                // Verify exactly 1 row exists with revision 1
                setCallerTenant(tenantA, conn1)
                val verifyStmt = conn1.prepareStatement(
                    "SELECT count(*), min(revision) FROM cloud_memory_records WHERE memory_id = ?"
                )
                verifyStmt.setString(1, memoryId)
                val rs = verifyStmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("Exactly 1 row should exist for initial concurrent insert", 1, rs.getInt(1))
                assertEquals(1L, rs.getLong(2))
            } finally {
                conn1.close()
                conn2.close()
            }
        }
        executor.shutdown()
    }

    @Test
    fun maxAllowedCiphertextAcceptedByClientAndServer() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        // Max allowed: exactly 524,288 bytes (512 KiB)
        val maxBytes = ByteArray(CloudCryptoService.MAX_CLOUD_CIPHERTEXT_BYTES) { 0x41.toByte() }
        val maxBase64 = Base64.getEncoder().encodeToString(maxBytes)

        val rpcStmt = conn.prepareStatement("SELECT (upsert_cloud_memory_record(?, ?, ?, ?, ?, ?))->>'status'")
        rpcStmt.setString(1, "mem-max-size-test")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, validNonce)
        rpcStmt.setString(5, maxBase64)
        rpcStmt.setBoolean(6, false)

        val rs = rpcStmt.executeQuery()
        assertTrue(rs.next())
        assertEquals("ACCEPTED", rs.getString(1))
    }

    @Test
    fun oneByteOverCiphertextRejectedByClientAndServer() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        // 1 byte over max allowed: 524,289 bytes
        val overBytes = ByteArray(CloudCryptoService.MAX_CLOUD_CIPHERTEXT_BYTES + 1) { 0x42.toByte() }
        val overBase64 = Base64.getEncoder().encodeToString(overBytes)

        // 1. Server must reject with exception
        try {
            val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
            rpcStmt.setString(1, "mem-over-size-test")
            rpcStmt.setLong(2, 1L)
            rpcStmt.setInt(3, 1)
            rpcStmt.setString(4, validNonce)
            rpcStmt.setString(5, overBase64)
            rpcStmt.setBoolean(6, false)
            rpcStmt.execute()
            fail("Server must reject ciphertext exceeding 512 KiB")
        } catch (e: SQLException) {
            assertTrue(
                "Expected size limit exception, got: ${e.message}",
                e.message?.contains("exceeds maximum allowed length") == true
            )
        }

        // 2. Client decrypt must also reject with CloudDecryptionException
        val record = EncryptedCloudRecord(
            memoryId = "mem-client-over",
            revision = 1L,
            payloadVersion = 1,
            nonceBase64 = validNonce,
            ciphertextBase64 = overBase64,
            isTombstone = false
        )
        try {
            CloudCryptoService.decryptMemory(record, ByteArray(32))
            fail("Client must reject ciphertext exceeding limit")
        } catch (e: CloudDecryptionException) {
            assertTrue(
                "Expected size limit exception on client, got: ${e.message}",
                e.message?.contains("exceeds maximum limit") == true
            )
        }
    }

    @Test
    fun invalidBase64NonceRejected() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        try {
            val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
            rpcStmt.setString(1, "mem-bad-nonce")
            rpcStmt.setLong(2, 1L)
            rpcStmt.setInt(3, 1)
            rpcStmt.setString(4, "!!!not_valid_base64!!!")
            rpcStmt.setString(5, "Y2lwaGVydGV4dA==")
            rpcStmt.setBoolean(6, false)
            rpcStmt.execute()
            fail("Server must reject non-base64 nonce")
        } catch (e: SQLException) {
            assertTrue(
                "Expected invalid nonce exception, got: ${e.message}",
                e.message?.contains("Invalid nonce") == true
            )
        }
    }

    @Test
    fun nonceNot12BytesRejected() {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        setCallerTenant(tenantA)

        // 11 bytes nonce
        val nonce11 = Base64.getEncoder().encodeToString(ByteArray(11))
        try {
            val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
            rpcStmt.setString(1, "mem-nonce-11")
            rpcStmt.setLong(2, 1L)
            rpcStmt.setInt(3, 1)
            rpcStmt.setString(4, nonce11)
            rpcStmt.setString(5, "Y2lwaGVydGV4dA==")
            rpcStmt.setBoolean(6, false)
            rpcStmt.execute()
            fail("Server must reject 11-byte nonce")
        } catch (e: SQLException) {
            assertTrue(
                "Expected 12 bytes nonce exception, got: ${e.message}",
                e.message?.contains("exactly 12 bytes") == true
            )
        }

        // 13 bytes nonce
        val nonce13 = Base64.getEncoder().encodeToString(ByteArray(13))
        try {
            val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
            rpcStmt.setString(1, "mem-nonce-13")
            rpcStmt.setLong(2, 1L)
            rpcStmt.setInt(3, 1)
            rpcStmt.setString(4, nonce13)
            rpcStmt.setString(5, "Y2lwaGVydGV4dA==")
            rpcStmt.setBoolean(6, false)
            rpcStmt.execute()
            fail("Server must reject 13-byte nonce")
        } catch (e: SQLException) {
            assertTrue(
                "Expected 12 bytes nonce exception, got: ${e.message}",
                e.message?.contains("exactly 12 bytes") == true
            )
        }
    }
}
