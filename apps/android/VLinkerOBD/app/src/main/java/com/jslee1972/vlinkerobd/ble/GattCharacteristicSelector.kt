package com.jslee1972.vlinkerobd.ble

data class GattCharacteristicCandidate(
    val serviceUuid: String,
    val characteristicUuid: String,
    val canNotify: Boolean,
    val canIndicate: Boolean,
    val canWrite: Boolean,
    val canWriteWithoutResponse: Boolean,
)

data class GattSelection(
    val notify: GattCharacteristicCandidate,
    val write: GattCharacteristicCandidate,
)

/**
 * Picks a compatible notify/indicate + write/write-without-response characteristic pair from a
 * GATT discovery without assuming any fixed vendor UUID (vLinker firmware varies). Notify is
 * preferred over Indicate, Write-Without-Response over Write, and a pair within the same service
 * is preferred over a cross-service pair. Remaining ties break on UUID string ordering so the
 * result is deterministic.
 */
object GattCharacteristicSelector {

    fun select(candidates: List<GattCharacteristicCandidate>): GattSelection? {
        val receivers = candidates
            .filter { it.canNotify || it.canIndicate }
            .sortedWith(
                compareByDescending<GattCharacteristicCandidate> { it.canNotify }
                    .thenBy { it.serviceUuid }
                    .thenBy { it.characteristicUuid }
            )
        val senders = candidates
            .filter { it.canWrite || it.canWriteWithoutResponse }
            .sortedWith(
                compareByDescending<GattCharacteristicCandidate> { it.canWriteWithoutResponse }
                    .thenBy { it.serviceUuid }
                    .thenBy { it.characteristicUuid }
            )

        if (receivers.isEmpty() || senders.isEmpty()) return null

        val pairs = receivers.flatMap { notify -> senders.map { write -> notify to write } }
        val best = pairs.minWithOrNull(
            compareByDescending<Pair<GattCharacteristicCandidate, GattCharacteristicCandidate>> {
                it.first.characteristicUuid == it.second.characteristicUuid
            }
                .thenByDescending { it.first.serviceUuid == it.second.serviceUuid }
                .thenByDescending { it.first.canNotify }
                .thenByDescending { it.second.canWriteWithoutResponse }
                .thenBy { it.first.characteristicUuid }
                .thenBy { it.second.characteristicUuid }
        ) ?: return null

        return GattSelection(notify = best.first, write = best.second)
    }
}
