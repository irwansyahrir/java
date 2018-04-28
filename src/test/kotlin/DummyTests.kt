import org.junit.Assert.*
import org.junit.Test

class DumbTest {
    @Test
    fun dumbTest() {
        val dumb = Dumb()
        assertEquals(dumb.hello(), "hello")
    }

}