package moe.lolosia.web.util.session;

import moe.lolosia.web.util.ebean.AbstractModel;
import moe.lolosia.web.util.ebean.ContextKt;
import io.ebean.Database;
import io.ebean.Transaction;
import io.ebean.typequery.IQueryBean;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

public class JavaContext extends Context {

    private final Context context;

    public JavaContext(Context context) {
        this.context = context;
    }

    @Override
    public @NotNull JavaContext java() {
        return this;
    }

    @Override
    public @NotNull ApplicationContext getApplicationContext() {
        return this.context.getApplicationContext();
    }

    @Override
    public @NotNull SessionMap getSession() {
        return this.context.getSession();
    }

    @NotNull
    public Context getBaseContext() {
        return this.context;
    }

    @NotNull
    public Database getDatabase() {
        return getDatabase("db");
    }

    @NotNull
    public Database getDatabase(@NotNull String name) {
        return ContextKt.database(this.context, name);
    }

    @NotNull
    public <T> Mono<T> transaction(@NotNull Function<Transaction, Mono<T>> block) {
        return transaction(getDatabase(), block);
    }

    @NotNull
    public <T> Mono<T> transaction(@NotNull Database database, @NotNull Function<Transaction, Mono<T>> block) {
        var transaction = database.createTransaction();
        try {
            return block.apply(transaction)
                    .doOnSuccess(it -> transaction.commit())
                    .doOnError(it -> transaction.rollback());
        } catch (Throwable e) {
            transaction.rollback();
        }
        return Mono.empty();
    }

    @NotNull
    public <T extends AbstractModel> T createModel(@NotNull Class<T> modelClass) {
        return createModel(modelClass, null);
    }

    @NotNull
    public <T extends AbstractModel> T createModel(
            @NotNull Class<T> modelClass,
            @Nullable Map<String, Object> bundle
    ) {
        return ContextKt.createModel(this, modelClass, bundle, false);
    }

    public <T extends IQueryBean> T query(Class<T> queryClass) {
        return ContextKt.query(this, queryClass);
    }
}
