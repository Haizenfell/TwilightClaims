package com.twilight.claims.managed;

import com.twilight.claims.api.managed.ManagedClaimSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ManagedClaimIndex {
    record Window(List<ManagedClaimSnapshot> claims, int nextOffset, UUID nextCursor, long generation) {
        Window { claims = List.copyOf(claims); }
    }

    private static final class Node {
        ManagedClaimSnapshot value;
        Node left;
        Node right;
        int height = 1;
        int size = 1;

        Node(ManagedClaimSnapshot value) { this.value = value; }
        UUID key() { return value.identity().instanceId(); }
    }

    private static final class ControllerIndex {
        Node root;
        long generation;
    }

    private final Map<String, ControllerIndex> controllers = new HashMap<>();

    void put(ManagedClaimSnapshot claim) {
        ControllerIndex controller = controllers.computeIfAbsent(claim.identity().controllerId(), key -> new ControllerIndex());
        controller.root = put(controller.root, claim);
        controller.generation++;
    }

    Window page(String controllerId, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid page range");
        ControllerIndex controller = controllers.get(controllerId);
        if (controller == null) return new Window(List.of(), -1, null, 0);
        return window(controller, offset, limit);
    }

    Window scan(String controllerId, UUID afterInstanceId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid page size");
        ControllerIndex controller = controllers.get(controllerId);
        if (controller == null) return new Window(List.of(), -1, null, 0);
        int offset = afterInstanceId == null ? 0 : countAtMost(controller.root, afterInstanceId);
        return window(controller, offset, limit);
    }

    private static Window window(ControllerIndex controller, int offset, int limit) {
        int count = size(controller.root);
        if (offset >= count) return new Window(List.of(), -1, null, controller.generation);
        int end = (int) Math.min((long) count, (long) offset + limit);
        List<ManagedClaimSnapshot> result = new ArrayList<>(end - offset);
        collect(controller.root, offset, end, result);
        boolean more = end < count;
        return new Window(result, more ? end : -1,
                more ? result.getLast().identity().instanceId() : null, controller.generation);
    }

    private static int countAtMost(Node node, UUID key) {
        int count = 0;
        while (node != null) {
            if (node.key().compareTo(key) <= 0) {
                count += size(node.left) + 1;
                node = node.right;
            } else node = node.left;
        }
        return count;
    }

    private static void collect(Node node, int from, int to, List<ManagedClaimSnapshot> result) {
        if (node == null || from >= to) return;
        int rank = size(node.left);
        if (from < rank) collect(node.left, from, Math.min(to, rank), result);
        if (from <= rank && rank < to) result.add(node.value);
        if (to > rank + 1) collect(node.right, Math.max(0, from - rank - 1), to - rank - 1, result);
    }

    private static Node put(Node node, ManagedClaimSnapshot value) {
        if (node == null) return new Node(value);
        int comparison = value.identity().instanceId().compareTo(node.key());
        if (comparison < 0) node.left = put(node.left, value);
        else if (comparison > 0) node.right = put(node.right, value);
        else {
            node.value = value;
            return node;
        }
        update(node);
        int balance = height(node.left) - height(node.right);
        if (balance > 1) {
            if (height(node.left.left) < height(node.left.right)) node.left = rotateLeft(node.left);
            return rotateRight(node);
        }
        if (balance < -1) {
            if (height(node.right.right) < height(node.right.left)) node.right = rotateRight(node.right);
            return rotateLeft(node);
        }
        return node;
    }

    private static Node rotateLeft(Node node) {
        Node pivot = node.right;
        node.right = pivot.left;
        pivot.left = node;
        update(node);
        update(pivot);
        return pivot;
    }

    private static Node rotateRight(Node node) {
        Node pivot = node.left;
        node.left = pivot.right;
        pivot.right = node;
        update(node);
        update(pivot);
        return pivot;
    }

    private static void update(Node node) {
        node.height = Math.max(height(node.left), height(node.right)) + 1;
        node.size = size(node.left) + size(node.right) + 1;
    }

    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static int size(Node node) { return node == null ? 0 : node.size; }
}
