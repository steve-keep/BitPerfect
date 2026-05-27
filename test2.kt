fun main() {
    val bestShift = 4 // meaning expected[4] == observed[0]
    // The user spec says: "ABCDEFG \n __ABCDEFG" -> Positive shift.
    // If observed is shifted right (i.e. delayed / preceded by __),
    // it means expected starts earlier. So expected[4] matches observed[0].
    // If the loop sets `startExpected = 4` and `startObserved = 0`, then `shift` was +4.
    // My code does: `val startExpected = if (shift > 0) shift else 0` -> so `shift` is +4.
    // Wait, why did the test return -4 then? Let's check my test case.

    // In my test:
    // val base = generateSequence(1, 100)
    // val expected = base.copyOf()
    // val observed = generateSequence(0, 4) + base.copyOfRange(0, 96 * 2)

    // Here, expected[0] == 1, expected[4] == 5.
    // observed[0..3] == 0..3
    // observed[4] == 1, observed[5] == 2, etc.
    // So expected[0] matches observed[4].
    // That means `startExpected = 0` and `startObserved = 4`.
    // In my code: `startObserved = if (shift < 0) -shift else 0`.
    // This happens when `shift` is -4.

    // BUT this observed is "ABCDEFG \n __ABCDEFG".
    // Wait, "ABCDEFG" is base. observed is "__" + base.
    // So observed is shifted *right* by 4 samples.
    // And this is the scenario the user called "Positive shift" in the spec!
    // Positive shift:
    // ABCDEFG
    // __ABCDEFG
    // Expected: shiftSamples > 0

    // Therefore, if startExpected = 0 and startObserved = 4, `shift` should be POSITIVE.
    // In my code, I used the classic convolution lag convention, but the user wants the opposite.
    // Let's fix this in DriftDetector.kt.
}
