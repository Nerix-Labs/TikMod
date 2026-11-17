package com.mts.tikmod.model

data class HookStatus(
    val name: String,
    val success: Boolean = false,
    val error: String? = null,
    val foundClass: String? = null
)
