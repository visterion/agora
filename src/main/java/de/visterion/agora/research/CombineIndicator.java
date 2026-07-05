package de.visterion.agora.research;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.Num;

import java.util.function.BinaryOperator;

/** Combines two indicators per index with a binary Num operation (e.g. minus). */
class CombineIndicator extends CachedIndicator<Num> {

    private final Indicator<Num> a;
    private final Indicator<Num> b;
    private final BinaryOperator<Num> op;
    private final int unstable;

    CombineIndicator(Indicator<Num> a, Indicator<Num> b, BinaryOperator<Num> op) {
        super(a);
        this.a = a;
        this.b = b;
        this.op = op;
        this.unstable = Math.max(a.getCountOfUnstableBars(), b.getCountOfUnstableBars());
    }

    @Override
    protected Num calculate(int index) {
        return op.apply(a.getValue(index), b.getValue(index));
    }

    @Override
    public int getCountOfUnstableBars() { return unstable; }
}
