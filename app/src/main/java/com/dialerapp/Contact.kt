package com.dialerapp

data class Contact(
    val id: Int,
    val name: String,
    val phone: String,
    var status: String = "pending",
    var notes: String = "",
    var calledAt: String = "",
    var source: String = "",
    var callDuration: String = ""
)
