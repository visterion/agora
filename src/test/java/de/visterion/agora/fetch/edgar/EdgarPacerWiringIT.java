package de.visterion.agora.fetch.edgar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wiring the rate contract rests on: ONE {@link EdgarRequestPacer} in the process, and
 * every EDGAR client drawing on that exact instance.
 *
 * <p>Without this, the fix is silently reversible — handing any one client its own pacer compiles,
 * passes every unit test (each of which is a one-instance process anyway) and only shows up as a
 * 403 from SEC in production. That is precisely how the budget came to be per-loop, then
 * per-class, before it was per-process.
 */
@SpringBootTest(properties = {
        "agora.auth.tokens=test-token",
        // Obviously fake contact address; SEC is never reached in this test.
        "agora.data.edgar.user-agent=Agora Test agora@example.com"})
class EdgarPacerWiringIT {

    @Autowired ApplicationContext ctx;
    @Autowired EdgarSearchService searchService;
    @Autowired EdgarService edgarService;
    @Autowired EdgarCikResolver cikResolver;

    @Test void everyEdgarClientSharesTheOnePacerBean() {
        assertThat(ctx.getBeansOfType(EdgarRequestPacer.class)).hasSize(1);
        EdgarRequestPacer pacer = ctx.getBean(EdgarRequestPacer.class);

        assertThat(searchService.pacer()).isSameAs(pacer);
        assertThat(edgarService.pacer()).isSameAs(pacer);
        assertThat(cikResolver.pacer()).isSameAs(pacer);
    }
}
