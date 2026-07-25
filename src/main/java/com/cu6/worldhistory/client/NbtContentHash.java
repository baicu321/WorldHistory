package com.cu6.worldhistory.client;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Produces a stable hash for logical NBT content, independent of compound-map iteration order. */
final class NbtContentHash {
    private NbtContentHash() { }

    static String sha256(CompoundTag tag) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeTag(output, tag);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to hash WorldHistory NBT", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeTag(DataOutputStream output, Tag tag) throws IOException {
        output.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> { }
            case Tag.TAG_BYTE -> output.writeByte(((NumericTag) tag).getAsByte());
            case Tag.TAG_SHORT -> output.writeShort(((NumericTag) tag).getAsShort());
            case Tag.TAG_INT -> output.writeInt(((NumericTag) tag).getAsInt());
            case Tag.TAG_LONG -> output.writeLong(((NumericTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> output.writeFloat(((NumericTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> output.writeDouble(((NumericTag) tag).getAsDouble());
            case Tag.TAG_BYTE_ARRAY -> writeByteArray(output, ((ByteArrayTag) tag).getAsByteArray());
            case Tag.TAG_STRING -> writeString(output, tag.getAsString());
            case Tag.TAG_LIST -> writeList(output, (ListTag) tag);
            case Tag.TAG_COMPOUND -> writeCompound(output, (CompoundTag) tag);
            case Tag.TAG_INT_ARRAY -> writeIntArray(output, ((IntArrayTag) tag).getAsIntArray());
            case Tag.TAG_LONG_ARRAY -> writeLongArray(output, ((LongArrayTag) tag).getAsLongArray());
            default -> throw new IllegalArgumentException("Unsupported NBT tag type: " + tag.getId());
        }
    }

    private static void writeCompound(DataOutputStream output, CompoundTag tag) throws IOException {
        var keys = tag.getAllKeys().stream().sorted().toList();
        output.writeInt(keys.size());
        for (String key : keys) {
            writeString(output, key);
            Tag value = tag.get(key);
            if (value == null) throw new IllegalStateException("WorldHistory NBT key has no value: " + key);
            writeTag(output, value);
        }
    }

    private static void writeList(DataOutputStream output, ListTag tag) throws IOException {
        output.writeInt(tag.size());
        for (Tag value : tag) writeTag(output, value);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeByteArray(DataOutputStream output, byte[] values) throws IOException {
        output.writeInt(values.length);
        output.write(values);
    }

    private static void writeIntArray(DataOutputStream output, int[] values) throws IOException {
        output.writeInt(values.length);
        for (int value : values) output.writeInt(value);
    }

    private static void writeLongArray(DataOutputStream output, long[] values) throws IOException {
        output.writeInt(values.length);
        for (long value : values) output.writeLong(value);
    }
}
