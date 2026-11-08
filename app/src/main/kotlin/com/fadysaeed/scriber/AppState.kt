package com.fadysaeed.scriber

import com.fadysaeed.scriber.core.ModelPack
import com.fadysaeed.scriber.fetch.Fetcher
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.lab.DeviceSnapshot
import com.fadysaeed.scriber.lab.LabReport

data class PackRow(val pack: ModelPack, val state: PackState, val bytesOnPhone: Long)

data class ModelsUi(
    val device: DeviceSnapshot? = null,
    val recommendedLlm: String? = null,
    val chosenLlm: String? = null,
    val rows: List<PackRow> = emptyList(),
    val fetch: Fetcher.Status = Fetcher.Status.Idle,
    val locked: Boolean = false,
    val wifiOnly: Boolean = true,
    val freeBytes: Long = 0,
    val neededBytes: Long = 0,
    val verifying: String? = null,
    val message: String? = null,
) {
    val selected: List<PackRow> get() = rows.filter { it.pack.required || it.pack.variant == chosenLlm }
    val allReady: Boolean get() = selected.isNotEmpty() && selected.all { it.state is PackState.Verified }
    val enoughSpace: Boolean get() = freeBytes >= neededBytes
}

data class LabUi(
    val step: String? = null,
    val report: LabReport? = null,
    val modelsReady: Boolean = false,
)

data class SecurityUi(
    val locked: Boolean = false,
    val hosts: List<String> = emptyList(),
    val vaultOk: Boolean? = null,
    val vaultDetail: String = "",
    val version: String = "",
    val catalogVersion: Int = 0,
)
