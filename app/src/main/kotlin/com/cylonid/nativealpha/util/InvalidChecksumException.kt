package com.cylonid.nativealpha.util

/** 备份校验和/格式版本校验失败（DataManager 恢复备份时抛出） */
class InvalidChecksumException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}
