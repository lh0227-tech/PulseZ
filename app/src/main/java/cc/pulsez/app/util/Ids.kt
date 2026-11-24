package cc.pulsez.app.util

import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString()
}
