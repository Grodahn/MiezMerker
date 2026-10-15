package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Production defense in depth; JPA exposes no update path on either dialect. */
public class V22__immutable_bowl_number extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        if (!context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE FUNCTION prevent_bowl_number_change() RETURNS trigger
                    LANGUAGE plpgsql AS $$ BEGIN
                        IF NEW.bowl_number IS DISTINCT FROM OLD.bowl_number THEN
                            RAISE EXCEPTION 'bowl_number is immutable';
                        END IF;
                        RETURN NEW;
                    END $$
                    """);
            statement.execute("CREATE TRIGGER nodes_bowl_number_immutable BEFORE UPDATE ON nodes "
                    + "FOR EACH ROW EXECUTE FUNCTION prevent_bowl_number_change()");
        }
    }
}
