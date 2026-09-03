package com.example.labdetect.domain

class OneShotEvent<out T>(private val value: T) {
    private var handled = false

    fun consume(): T? = if (handled) null else value.also { handled = true }
}
