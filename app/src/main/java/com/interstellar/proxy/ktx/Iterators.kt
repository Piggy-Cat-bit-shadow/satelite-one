package com.interstellar.proxy.ktx

import android.net.IpPrefix
import android.os.Build
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.StringIterator
import java.net.InetAddress

class StringArray(private val iterator: Iterator<String>) : StringIterator {
    override fun len(): Int = 0

    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): String = iterator.next()
}

fun Iterable<String>.toStringIterator(): StringIterator = StringArray(iterator())

fun StringIterator.toList(): List<String> = mutableListOf<String>().apply {
    while (hasNext()) {
        add(next())
    }
}

fun LogIterator.toList(): List<LogEntry> = mutableListOf<LogEntry>().apply {
    while (hasNext()) {
        add(next())
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RoutePrefix.toIpPrefix() = IpPrefix(InetAddress.getByName(address().value), prefix())
