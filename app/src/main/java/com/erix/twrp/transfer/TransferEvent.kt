package com.erix.twrp.transfer
sealed class TransferEvent {
    data class Progress(val done: Long, val total: Long, val speedBps: Long, val etaSec: Long) : TransferEvent()
    data class Disconnected(val committed: Long) : TransferEvent()
    data class Reconnected(val serial: String) : TransferEvent()
    data class Success(val finalPath: String, val total: Long) : TransferEvent()
    data class Failure(val committed: Long, val reason: String) : TransferEvent()
    data class ChunkRetry(val attempt: Int, val max: Int, val why: String) : TransferEvent()
}
