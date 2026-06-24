package com.legendfool.foollegends

import kotlin.random.Random

/** Four colors. */
enum class GameColor(val display: String, val rgb: Int) {
    RED("RED", 0xFFFF453A.toInt()),
    BLUE("BLUE", 0xFF0A84FF.toInt()),
    GREEN("GREEN", 0xFF32D74B.toInt()),
    YELLOW("YELLOW", 0xFFFFD60A.toInt());

    companion object {
        fun random(rnd: Random = Random.Default): GameColor = entries[rnd.nextInt(entries.size)]
    }
}

/**
 * One round. ONE simple, intuitive rule the whole game: tap the color the [word] NAMES.
 *
 * The Joker's deception is purely visual and never changes the rule:
 *  - [ink] paints the word in a lying color (Stroop interference),
 *  - [layout] shuffles which color sits on each of the 4 buttons.
 *
 * @param layout colors placed on grid positions 0..3 (RED/BLUE/GREEN/YELLOW slots).
 */
data class Round(
    val word: GameColor,
    val ink: GameColor,
    val timeMs: Long,
    val layout: List<GameColor>
) {
    val answer: GameColor get() = word
    val mismatch: Boolean get() = ink != word
}

/**
 * Difficulty curve. Every correct answer raises the level by one: the timer shrinks, the
 * lying ink shows up more often, and from level 6 the buttons start swapping places.
 */
object LevelRules {

    private const val SHUFFLE_FROM = 6

    fun timeMs(level: Int): Long = maxOf(700L, 2500L - (level - 1) * 60L)

    private fun mismatchProbability(level: Int): Double = when {
        level <= 1 -> 0.0
        else -> minOf(0.92, 0.4 + (level - 2) * 0.05)
    }

    fun generate(level: Int, rnd: Random = Random.Default): Round {
        val word = GameColor.random(rnd)
        val ink = if (rnd.nextDouble() < mismatchProbability(level)) differentFrom(word, rnd) else word
        val layout = if (level >= SHUFFLE_FROM) GameColor.entries.shuffled(rnd) else GameColor.entries.toList()
        return Round(word, ink, timeMs(level), layout)
    }

    private fun differentFrom(c: GameColor, rnd: Random): GameColor {
        var x = GameColor.random(rnd)
        while (x == c) x = GameColor.random(rnd)
        return x
    }
}
