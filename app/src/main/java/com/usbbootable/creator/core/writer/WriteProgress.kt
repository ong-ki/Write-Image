package com.usbbootable.creator.core.writer

import com.usbbootable.creator.core.image.BootSupportSummary

enum class WritePhase {
    IDLE,
    PREPARING,
    PARTITIONING,
    WRITING,
    FLUSHING_CACHE,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED;

    val isActive: Boolean
        get() = this == PREPARING ||
            this == PARTITIONING ||
            this == WRITING ||
            this == FLUSHING_CACHE ||
            this == VERIFYING
}

data class VerificationReport(
    val verified: Boolean,
    val methodDescription: String,
    val bytesVerified: Long,
    val filesVerified: Int = 0,
    val sourceSha256: String? = null,
    val driveSha256: String? = null,
    val mismatchDetail: String? = null
)

data class WriteProgress(
    val phase: WritePhase = WritePhase.IDLE,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val phaseProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val speedBytesPerSec: Double = 0.0,
    val etaSeconds: Long = -1L,
    val currentItemLabel: String = "",
    val statusMessage: String = "Ready to create a bootable USB drive.",
    val targetDeviceName: String = "",
    val sourceIsoName: String = "",
    val bootSupport: BootSupportSummary? = null,
    val verificationReport: VerificationReport? = null,
    val errorMessage: String? = null
)
