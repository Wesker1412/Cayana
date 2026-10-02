package com.cayana.core.common

sealed interface Result<out T> {
    data class Success<out T>(val data: T) : Result<T>
    data class Error(val exception: Throwable, val message: String? = exception.message) : Result<Nothing>
    data object Loading : Result<Nothing>
}
