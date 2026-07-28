package de.visterion.agora.fetch.earnings;

import java.util.List;

/**
 * An earnings answer plus how much of it we can vouch for.
 *
 * <p>{@code partial} means a provider that was needed for this window failed, was cooled, or
 * ran out of budget — so an empty {@code events} list is "we could not see everything", not
 * "nothing is scheduled". Distinguishing the two is the whole point of this type: the previous
 * design collapsed them into an exception and made every earnings-free symbol look like an outage.
 */
public record EarningsResult(List<EarningsEvent> events, boolean partial) {}
