package org.miezmerker.backend.bootstrap;

import java.util.List;

/** Versioned DEMO ONLY master data. Chip IDs are literal strings, never Cat UUIDs. */
public final class DemoFixtures {
    private DemoFixtures() {}
    public static final String VERSION = "DEMO-ONLY-issue111-v1";
    public static final String EMAIL = "admin@miezmerker.local";
    public static final String SLUG = "miezmerker-demo";
    public static final String ORG_NAME = "MiezMerker Demo";
    public static final String SITE_NAME = "Zum schnurrenden Löffel";
    public static final String SITE_DESCRIPTION =
            "Das kleine Katzenbistro: Hier gibt es volle Näpfe und zufriedene Schnurrkonzerte.";
    public record CatFixture(String chipId, String name, String description) {}
    public static final List<CatFixture> CATS = List.of(
            new CatFixture("1", "Herr Krümel", "Kontrolliert jeden Napf und trägt die letzten Krümel stolz im Schnurrbart."),
            new CatFixture("2", "Frau Zimt", "Eine sanfte Sonnenanbeterin, die Streicheleinheiten mit leisem Schnurren quittiert."),
            new CatFixture("3", "Käpt'n Socke", "Mutiger Gartenentdecker mit weißen Pfoten und großem Appetit auf Abenteuer."),
            new CatFixture("4", "Lotte Löffel", "Die neugierige Küchenchefin schaut erst in alle Näpfe und entscheidet dann."),
            new CatFixture("5", "Professor Flausch", "Denkt lange über das Futter nach und schläft anschließend auf seinen Erkenntnissen."));
}
