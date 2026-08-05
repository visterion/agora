package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * One protective order that replaced a cancelled one during a partial close.
 *
 * <p>{@code replaces} is the id the caller has in its book; {@code orderId} is the new id the
 * broker issued. Callers key on {@code replaces} so they can repoint the right column without
 * guessing which leg is which.
 */
public record RestoredLeg(String replaces, String orderId, BigDecimal qty, BigDecimal price) {}
