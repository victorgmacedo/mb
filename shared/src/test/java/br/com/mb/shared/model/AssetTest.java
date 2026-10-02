package br.com.mb.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AssetTest {

    @Test
    void createsAssetWithSymbol() {
        var asset = new Asset("BTC");

        assertEquals("BTC", asset.symbol());
    }

    @Test
    void rejectsBlankSymbol() {
        assertThrows(IllegalArgumentException.class, () -> new Asset(" "));
    }
}
