package dev.primetech.ads.adserver;

/**
 * The ad server's view of budget pacing: how often each direct line item may compete, and the
 * running total of what it has served. Must be cheap to call per request.
 */
public interface PacingView {

    /** Probability the line item enters this auction; 0 once its budget is spent. */
    double throttle(String lineItemId);

    /** Records a served impression's price against the line item's budget. */
    void served(String lineItemId, long priceMicros);

    /** Every line item at the same fixed participation and no budget: for tests and running without Redis. */
    static PacingView fixed(double participation) {
        return new PacingView() {
            @Override
            public double throttle(String lineItemId) {
                return participation;
            }

            @Override
            public void served(String lineItemId, long priceMicros) {
            }
        };
    }
}
