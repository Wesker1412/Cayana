package com.cayana.cloud

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
import java.util.UUID

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

                // Read and execute migration script
                val migrationFile = File("supabase/migrations/20261008000000_cloud_memory_records.sql")
                val migrationSql = if (migrationFile.exists()) {
                    migrationFile.readText()
                } else {
                    File("../supabase/migrations/20261008000000_cloud_memory_records.sql").readText()
                }

                statement.execute(migrationSql)

                // Create standard unprivileged Supabase role 'authenticated'
                // PostgreSQL superusers always bypass RLS; unprivileged roles are strictly subject to RLS policies.
                statement.execute("DO ${'$'}${'$'} BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated; END IF; END ${'$'}${'$'};")
                statement.execute("GRANT USAGE ON SCHEMA public TO authenticated;")
                statement.execute("GRANT USAGE ON SCHEMA auth TO authenticated;")
                statement.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO authenticated;")
                statement.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO authenticated;")
                statement.execute("GRANT ALL PRIVILEGES ON ALL ROUTINES IN SCHEMA public TO authenticated;")
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

    private fun setCallerTenant(tenantId: UUID?) {
        val conn = connection ?: throw IllegalStateException("Database not connected")
        val stmt = conn.createStatement()
        stmt.execute("RESET ROLE;")
        if (tenantId != null) {
            stmt.execute("SET request.jwt.claim.sub = '$tenantId';")
        } else {
            stmt.execute("SET request.jwt.claim.sub = '';")
        }
        stmt.execute("SET ROLE authenticated;")
    }

    private val tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun tenantACannotReadTenantB() {
        val conn = connection ?: throw IllegalStateException("Database not connected")

        // 1. Tenant B inserts a record via RPC
        setCallerTenant(tenantB)
        val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcStmt.setString(1, "mem-tenant-b-secret")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, "bm9uY2UxMjM0NTY=") // 12-byte base64 nonce
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
        rpcStmt.setString(4, "bm9uY2UxMjM0NTY=")
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
        // RPC uses auth.uid() inside, so it creates under Tenant A's own owner_id!
        val rpcA = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcA.setString(1, "mem-tenant-b-target")
        rpcA.setLong(2, 2L)
        rpcA.setInt(3, 1)
        rpcA.setString(4, "bm9uY2UxMjM0NTY=")
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
    fun tenantACannotDeleteTenantB() {
        val conn = connection ?: throw IllegalStateException("Database not connected")

        // 1. Ensure Tenant B record exists
        setCallerTenant(tenantB)
        val rpcStmt = conn.prepareStatement("SELECT upsert_cloud_memory_record(?, ?, ?, ?, ?, ?)")
        rpcStmt.setString(1, "mem-tenant-b-nodelete")
        rpcStmt.setLong(2, 1L)
        rpcStmt.setInt(3, 1)
        rpcStmt.setString(4, "bm9uY2UxMjM0NTY=")
        rpcStmt.setString(5, "bm9fZGVsZXRlX2NpcGhlcg==")
        rpcStmt.setBoolean(6, false)
        rpcStmt.execute()

        // 2. Tenant A attempts direct SQL DELETE
        setCallerTenant(tenantA)
        val deleteStmt = conn.prepareStatement("DELETE FROM cloud_memory_records WHERE memory_id = ?")
        deleteStmt.setString(1, "mem-tenant-b-nodelete")
        val rowsDeleted = deleteStmt.executeUpdate()
        assertEquals("Tenant A delete on Tenant B record must affect 0 rows", 0, rowsDeleted)

        // 3. Verify Tenant B record still exists
        setCallerTenant(tenantB)
        val selectB = conn.prepareStatement("SELECT count(*) FROM cloud_memory_records WHERE memory_id = ?")
        selectB.setString(1, "mem-tenant-b-nodelete")
        val rs = selectB.executeQuery()
        assertTrue(rs.next())
        assertEquals(1, rs.getInt(1))
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
        rpcStmt.setString(4, "bm9uY2UxMjM0NTY=")
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
            rpcUnauth.setString(4, "bm9uY2UxMjM0NTY=")
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
        stmt1.setString(4, "bm9uY2UxMjM0NTY=")
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
        stmtRetry.setString(4, "bm9uY2UxMjM0NTY=")
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
        stmt2.setString(4, "bm9uY2UxMjM0NTY=")
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
        stmtStale.setString(4, "bm9uY2UxMjM0NTY=")
        stmtStale.setString(5, "Y2lwaGVyX3N0YWxl")
        stmtStale.setBoolean(6, false)
        val rsStale = stmtStale.executeQuery()
        assertTrue(rsStale.next())
        assertEquals("STALE", rsStale.getString(1))
    }
}
