package br.rustland.killmoney;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Leitor minimo de arquivos .schem (formato Sponge v2/v3 do WorldEdit): so os blocos, sem entidades. */
final class SchematicData {

    final int width;
    final int height;
    final int length;
    /** Estado de bloco por indice da paleta, ex.: "minecraft:grass_block[snowy=false]". */
    final String[] palette;
    /** Indice da paleta de cada bloco, ordem (y * length + z) * width + x. */
    final int[] ids;

    private SchematicData(int width, int height, int length, String[] palette, int[] ids) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.ids = ids;
    }

    String stateAt(int x, int y, int z) {
        return palette[ids[(y * length + z) * width + x]];
    }

    @SuppressWarnings("unchecked")
    static SchematicData read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            int rootType = in.readByte();
            if (rootType != 10) {
                throw new IOException("Arquivo .schem invalido (raiz nao e um compound).");
            }
            in.readUTF();
            Map<String, Object> root = (Map<String, Object>) payload(10, in);
            Map<String, Object> s = root.containsKey("Schematic") ? (Map<String, Object>) root.get("Schematic") : root;

            int w = ((Number) need(s, "Width")).intValue();
            int h = ((Number) need(s, "Height")).intValue();
            int l = ((Number) need(s, "Length")).intValue();

            Map<String, Object> paletteTag;
            byte[] data;
            if (s.containsKey("Blocks")) {
                Map<String, Object> b = (Map<String, Object>) s.get("Blocks");
                paletteTag = (Map<String, Object>) need(b, "Palette");
                data = (byte[]) need(b, "Data");
            } else {
                paletteTag = (Map<String, Object>) need(s, "Palette");
                data = (byte[]) need(s, "BlockData");
            }
            String[] palette = new String[paletteTag.size()];
            for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
                int idx = ((Number) e.getValue()).intValue();
                if (idx < 0 || idx >= palette.length) {
                    throw new IOException("Paleta invalida no .schem.");
                }
                palette[idx] = e.getKey();
            }
            int total = w * h * l;
            int[] ids = new int[total];
            int pos = 0;
            for (int i = 0; i < total; i++) {
                int value = 0;
                int shift = 0;
                while (true) {
                    if (pos >= data.length) {
                        throw new IOException("Dados de blocos incompletos no .schem.");
                    }
                    int b = data[pos++];
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                if (value >= palette.length) {
                    throw new IOException("Indice fora da paleta no .schem.");
                }
                ids[i] = value;
            }
            return new SchematicData(w, h, l, palette, ids);
        }
    }

    private static Object need(Map<String, Object> m, String key) throws IOException {
        Object v = m.get(key);
        if (v == null) {
            throw new IOException("Campo '" + key + "' nao encontrado no .schem.");
        }
        return v;
    }

    private static Object payload(int type, DataInputStream in) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int itemType = in.readByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(payload(itemType, in));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) {
                        return map;
                    }
                    String key = in.readUTF();
                    map.put(key, payload(t, in));
                }
            }
            case 11: {
                int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("Tag NBT desconhecida: " + type);
        }
    }
}
