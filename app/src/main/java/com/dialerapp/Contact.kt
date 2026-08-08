package com.dialerapp

data class CallRecord(
    val status: String,
    val notes: String,
    val calledAt: String,
    val callDuration: String
)

data class Contact(
    val id: Int,
    val name: String,
    val phone: String,
    var status: String = "pending",
    var notes: String = "",
    var calledAt: String = "",
    var source: String = "",
    var callDuration: String = "",
    var callHistory: MutableList<CallRecord> = mutableListOf()
)
