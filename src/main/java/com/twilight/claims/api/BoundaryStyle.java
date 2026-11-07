package com.twilight.claims.api;

import org.bukkit.Material;

import java.util.Objects;

public record BoundaryStyle(Material corner, Material edge, Material selectedCorner,
                            Material errorCorner, Material errorEdge) {
    public BoundaryStyle {
        validate(corner, "corner");
        validate(edge, "edge");
        validate(selectedCorner, "selectedCorner");
        validate(errorCorner, "errorCorner");
        validate(errorEdge, "errorEdge");
    }

    public BoundaryStyle(Material corner, Material edge) {
        this(corner, edge, corner, corner, edge);
    }

    private static void validate(Material material, String name) {
        Objects.requireNonNull(material, name);
        if (!material.isBlock() || material.isAir()) {
            throw new IllegalArgumentException(name + " must be a non-air block material: " + material);
        }
    }
}
