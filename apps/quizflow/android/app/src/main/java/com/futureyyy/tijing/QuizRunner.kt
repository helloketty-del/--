package com.futureyyy.tijing

interface QuizRunner {
    fun onPrimaryAction()
    fun pause()
    fun resume()
    fun retry()
    fun stop()
    fun snapshot(): RunnerSnapshot
}
