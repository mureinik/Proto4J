package sexy.kostya.proto4j.rpc;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by k.shandurenko on 02.10.2020
 */
public class TestServiceImpl implements TestService {

    private AtomicInteger value = new AtomicInteger();

    @Override
    public void set(int a, int b) {
        this.value.set(a + b);
    }

    @Override
    public CompletionStage<Void> setWithFuture(int a, int b) {
        set(a, b);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public int get() {
        return this.value.get();
    }

    @Override
    public CompletionStage<Integer> sum(int a, int b, int c) {
        return CompletableFuture.completedFuture(a + b + c);
    }

    @Override
    public int sumArray(int[] array) {
        int result = 0;
        for (int el : array) {
            result += el;
        }
        return result;
    }

    @Override
    public int sumList(List<Integer> array) {
        int result = 0;
        for (int el : array) {
            result += el;
        }
        return result;
    }
}
