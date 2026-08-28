package io.mszymanski.orknux.server.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest

/*
 * How loud this installation is, set from the environment. Issue #301.
 *
 * The assertions are on the variable name rather than on the Spring property,
 * and that is the whole point of them. `logging.level.io.mszymanski.orknux` was
 * always settable - it is Boot's own property and nobody had to add it. What did
 * not exist was a way to say so in the environment, which is the only way anyone
 * configures a container, and the placeholder in `application.yml` is the piece
 * that joins the two. Assert the property and these pass with that placeholder
 * deleted; assert the variable and they do not.
 *
 * Two classes rather than two nested ones, because each needs a context of its
 * own and surefire only collects a class whose name ends in `Test`. Written as
 * nested classes first, they were quietly never run by `mvn test` - they passed
 * when named on the command line and were invisible in the suite, which is
 * worse than not having been written.
 *
 * **There is no test for the default.** There was one, and it was a trap: a
 * logback level is set on a JVM-wide logger context, Spring caches and reuses
 * test contexts, and nothing puts a level back when one is torn down. So a
 * "nothing is said, therefore INFO" assertion passes or fails on which of these
 * classes the runner reached first. Both of the tests below set a value and
 * assert that value, which is true whatever ran before them.
 */

@SpringBootTest(properties = ["ORKNUX_LOG_LEVEL=DEBUG"])
class LogLevelApplicationTest {

    @Test
    fun `this application says more, and the rest of the classpath does not`() {
        assertThat(LoggerFactory.getLogger("io.mszymanski.orknux.server").isDebugEnabled).isTrue()
        // The reason there are two variables: raising one must not raise the other.
        assertThat(LoggerFactory.getLogger("org.hibernate").isDebugEnabled).isFalse()
    }
}

@SpringBootTest(properties = ["ORKNUX_LOG_LEVEL_ROOT=WARN"])
class LogLevelRootTest {

    @Test
    fun `the rest of the classpath goes quiet`() {
        assertThat(LoggerFactory.getLogger("org.hibernate").isInfoEnabled).isFalse()
        assertThat(LoggerFactory.getLogger("org.hibernate").isWarnEnabled).isTrue()
    }
}
