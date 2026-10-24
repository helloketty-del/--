package com.screencopy.touch;
import org.junit.Test;
import static org.junit.Assert.*;
public class DockMathTest {
    @Test public void dragCannotLeaveSafeScreen() {
        assertEquals(30,DockMath.clamp(-100,30,950));
        assertEquals(950,DockMath.clamp(5000,30,950));
        assertEquals(400,DockMath.clamp(400,30,950));
    }
    @Test public void nearestEdgeRespectsInsets() {
        assertEquals(20,DockMath.edge(200,20,920));
        assertEquals(920,DockMath.edge(700,20,920));
        assertEquals(20,DockMath.edge(470,20,920));
    }
    @Test public void rotationRetainsRelativeHeight() {
        float fraction=DockMath.fraction(1020,20,2020);
        assertEquals(.5f,fraction,0f);
        assertEquals(520,DockMath.fromFraction(fraction,20,1020));
    }
    @Test public void tinyScreenAndInvalidSavedPositionStayBounded() {
        assertEquals(30,DockMath.clamp(500,30,10));
        assertEquals(30,DockMath.fromFraction(.5f,30,30));
        assertEquals(520,DockMath.fromFraction(Float.NaN,20,1020));
        assertEquals(1020,DockMath.fromFraction(2,20,1020));
        assertEquals(20,DockMath.fromFraction(-1,20,1020));
    }
}
