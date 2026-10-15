package com.heli.gis;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 松散(exploded)瓦片 -> 紧凑(compact/bundle)瓦片 转换程序。
 *
 * 与 {@link CompactToExploded} 互逆：把 _alllayers/Lxx/R{row}/C{col}.{png|jpg} 的松散瓦片，
 * 重新打包为 ArcGIS 10.2 compact v1 缓存（.bundle 数据文件 + .bundlx 索引文件）。
 *
 * 格式说明（已通过对原始 bundle 逐字节逆向验证）：
 *
 * 1) .bundle 数据文件：
 *    - [0, 60)        : 60 字节文件头（字段见下）
 *    - [60, 65596)    : 16384 个 4 字节槽位区（全 0，空瓦片的索引偏移指向这里，读取长度为 0）
 *    - [65596, end)   : 数据区，非空瓦片按 k=j*128+i 升序依次写入 [4字节长度][瓦片数据]
 *    文件头字段（4 字节小端 int，15 个 = 60 字节）：
 *        offset 0  : version = 3
 *        offset 4  : cacheSize = 16384 (128*128)
 *        offset 8  : 本块内最大瓦片数据长度（已对原始 bundle 逐层验证，除 L06 原始值存在一个小bug外
 *                     L07-L11 全部精确等于最大瓦片字节数；ArcGIS 据此分配读取缓冲，不可写小）
 *        offset 12 : entrySize = 5
 *        offset 16 : 4 * 非空瓦片数（已由样本验证）
 *        offset 20 : 0
 *        offset 24 : 文件总字节数（已由样本验证）
 *        offset 28 : 0
 *        offset 32 : 40
 *        offset 36 : 0
 *        offset 40 : 16
 *        offset 44 : baseRow（本块基准行，已对原始 bundle 验证）
 *        offset 48 : rowGroup + 127（本块最大行，已由样本验证）
 *        offset 52 : baseCol（本块基准列，已对原始 bundle 验证）
 *        offset 56 : colGroup + 127（本块最大列，已由样本验证）
 *
 * 2) .bundlx 索引文件（81952 字节）：
 *    - [0, 16) : 头 [3, 16, 16384, 5]
 *    - [16, 81936) : 16384 个条目，每个 5 字节小端偏移，指向该瓦片在 .bundle 中的
 *                     [4字节长度] 起始位置；空瓦片指向槽位区（读出长度为 0）
 *    - [81936, 81952) : 尾部固定 [0, 16, 16, 0]
 *
 * 3) bundle 命名：R{基准行}C{基准列}（十六进制，%04x），基准行 = (row/128)*128、基准列 = (col/128)*128。
 *    参考读取程序 CompactToExploded 解析该名得到基准行/列，再以 行=基准+i、列=基准+j 还原绝对坐标，
 *    因此名称必须写块基准坐标（128 的整数倍），才能互逆往返。
 */
public class ExplodedToCompactArcGIS {

    private static final String _BUNDLE_DATA_SUFFIX = ".bundle";
    private static final String _BUNDLE_IDX_SUFFIX = ".bundlx";
    private static final String FORMAT_ERROR_ROOT_DIRECTORY = "root directory '%s' is not found or it is not Directory.";
    private static final String FORMAT_ERROR_LAYERS_DIRECTORY = "_alllayers is not in root directory or it is not Directory.";

    private static final int SIZE = 128;
    private static final int SIZE0 = SIZE * SIZE;               // 16384
    private static final int DATA_HEADER_SIZE = 60;             // .bundle 文件头字节数
    private static final int SLOT_AREA_BYTES = SIZE0 * 4;       // 槽位区字节数 65536
    private static final int INDEX_HEADER_SIZE = 16;            // .bundlx 头字节数
    private static final int INDEX_ENTRY_SIZE = 5;              // 每个索引条目字节数
    private static final int INDEX_TAIL_SIZE = 16;              // .bundlx 尾部固定字节数

    // 层级目录过滤器：以 L/l 开头，其余为数字（与参考程序一致）
    private final FilenameFilter layerFilter = (File dir, String name) -> {
        if (!new File(dir, name).isDirectory() || name.length() < 2) {
            return false;
        }
        char first = name.charAt(0);
        if (first != 'L' && first != 'l') {
            return false;
        }
        return isAllDigits(name.substring(1));
    };

    // 行目录过滤器：以 R/r 开头，其余为十六进制数字
    private final FilenameFilter rowDirFilter = (File dir, String name) ->
            new File(dir, name).isDirectory() && name.length() > 1
                    && (name.charAt(0) == 'R' || name.charAt(0) == 'r')
                    && isAllHex(name.substring(1));

    // 列文件过滤器：C{hex}.{ext}
    private final FilenameFilter colFileFilter = (File dir, String name) -> {
        if (!new File(dir, name).isFile() || name.length() < 3) {
            return false;
        }
        char first = name.charAt(0);
        if (first != 'C' && first != 'c') {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 1 || dot == name.length() - 1) {
            return false;
        }
        return isAllHex(name.substring(1, dot));
    };

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) {
                    throw new IOException("unexpected EOF: " + f.getPath());
                }
                off += n;
            }
            return data;
        }
    }

    // 写 4 字节小端 int
    private void writeIntLE(DataOutputStream dos, int v) throws IOException {
        dos.write(v & 0xff);
        dos.write((v >> 8) & 0xff);
        dos.write((v >> 16) & 0xff);
        dos.write((v >> 24) & 0xff);
    }

    // 写 5 字节小端 long（最大 2^40）
    private void writeLong5LE(DataOutputStream dos, long v) throws IOException {
        dos.write((int) (v & 0xff));
        dos.write((int) ((v >> 8) & 0xff));
        dos.write((int) ((v >> 16) & 0xff));
        dos.write((int) ((v >> 24) & 0xff));
        dos.write((int) ((v >> 32) & 0xff));
    }

    /**
     * 把一个 128x128 块内的松散瓦片打包成单个 bundle。
     *
     * @param baseRow    块的基准行（= 行组索引 * 128，即 (row/128)*128）
     * @param baseCol    块的基准列（= (col/128)*128）
     * @param tiles      键为 k=j*128+i 的瓦片数据（k 由块内行 i、列 j 决定），缺省为空瓦片
     * @param cLayerPath 目标紧凑层目录（_alllayers/Lxx）
     */
    public void exploded2compactBundle(int baseRow, int baseCol, Map<Integer, byte[]> tiles, File cLayerPath) {
        String bundle = String.format("R%04xC%04x", baseRow, baseCol);
        File fDataFile = new File(cLayerPath, bundle + _BUNDLE_DATA_SUFFIX);
        File fIdxFile = new File(cLayerPath, bundle + _BUNDLE_IDX_SUFFIX);

        int nonEmpty = tiles.size();
        int maxTileLen = 0;                       // 最大瓦片数据长度
        for (byte[] d : tiles.values()) {
            if (d.length > maxTileLen) {
                maxTileLen = d.length;
            }
        }

        // 第一遍：计算每个瓦片的偏移与文件总大小
        long[] offset = new long[SIZE0];
        long dataPos = DATA_HEADER_SIZE + (long) SLOT_AREA_BYTES;   // 数据区起始 = 65596
        for (int k = 0; k < SIZE0; k++) {
            byte[] data = tiles.get(k);
            if (data != null) {
                offset[k] = dataPos;
                dataPos += 4L + data.length;
            } else {
                offset[k] = DATA_HEADER_SIZE + (long) k * 4;        // 指向槽位区，读出长度 0
            }
        }
        long fileSize = dataPos;

        // 写 .bundle 数据文件
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fDataFile)))) {
            writeIntLE(dos, 3);                // version
            writeIntLE(dos, SIZE0);            // cacheSize
            writeIntLE(dos, maxTileLen);       // 最大瓦片数据长度（已对原始 bundle 验证：= f2）
            writeIntLE(dos, INDEX_ENTRY_SIZE); // entrySize
            writeIntLE(dos, 4 * nonEmpty);     // 4 * 非空瓦片数
            writeIntLE(dos, 0);
            writeIntLE(dos, (int) fileSize);   // 文件总字节数
            writeIntLE(dos, 0);
            writeIntLE(dos, 40);
            writeIntLE(dos, 0);
            writeIntLE(dos, 16);
            writeIntLE(dos, baseRow);          // 本块基准行（ArcGIS 依此定位 bundle）
            writeIntLE(dos, baseRow + 127);    // 本块最大行
            writeIntLE(dos, baseCol);          // 本块基准列（ArcGIS 依此定位 bundle）
            writeIntLE(dos, baseCol + 127);    // 本块最大列

            // 槽位区：65536 字节全 0
            byte[] zeros = new byte[SLOT_AREA_BYTES];
            dos.write(zeros);

            // 数据区：非空瓦片按 k 升序写 [4字节长度][数据]
            for (int k = 0; k < SIZE0; k++) {
                byte[] data = tiles.get(k);
                if (data != null) {
                    writeIntLE(dos, data.length);
                    dos.write(data);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 写 .bundlx 索引文件
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fIdxFile)))) {
            writeIntLE(dos, 3);                // version
            writeIntLE(dos, INDEX_HEADER_SIZE);// 16
            writeIntLE(dos, SIZE0);            // 16384
            writeIntLE(dos, INDEX_ENTRY_SIZE); // 5
            for (int k = 0; k < SIZE0; k++) {
                writeLong5LE(dos, offset[k]);
            }
            // 尾部固定 16 字节
            writeIntLE(dos, 0);
            writeIntLE(dos, 16);
            writeIntLE(dos, 16);
            writeIntLE(dos, 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 把一层（_alllayers/Lxx）内的所有松散瓦片按 128x128 块分组并打包成 bundle。
     *
     * @param eLayerPath 松散层目录
     * @param cLayerPath 目标紧凑层目录
     */
    public void exploded2compactLayer(File eLayerPath, File cLayerPath) {
        if (!cLayerPath.exists()) {
            cLayerPath.mkdir();
        }

        // 键 = (baseRow << 32) | baseCol
        Map<Long, Map<Integer, byte[]>> bundleMap = new HashMap<>();

        File[] rowDirs = eLayerPath.listFiles(rowDirFilter);
        if (rowDirs == null) {
            return;
        }
        for (File rowDir : rowDirs) {
            long row = Long.parseLong(rowDir.getName().substring(1), 16);
            File[] colFiles = rowDir.listFiles(colFileFilter);
            if (colFiles == null) {
                continue;
            }
            for (File colFile : colFiles) {
                String name = colFile.getName();
                int dot = name.lastIndexOf('.');
                long col = Long.parseLong(name.substring(1, dot), 16);

                int baseRow = (int) ((row / SIZE) * SIZE);       // 块基准行
                int baseCol = (int) ((col / SIZE) * SIZE);       // 块基准列
                int i = (int) (row - baseRow);                   // 块内行 0..127
                int j = (int) (col - baseCol);                   // 块内列 0..127
                int k = j * SIZE + i;

                long key = ((long) baseRow << 32) | (baseCol & 0xffffffffL);
                try {
                    bundleMap.computeIfAbsent(key, x -> new HashMap<>()).put(k, readFile(colFile));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }

        for (Map.Entry<Long, Map<Integer, byte[]>> e : bundleMap.entrySet()) {
            long key = e.getKey();
            int baseRow = (int) (key >> 32);
            int baseCol = (int) (key & 0xffffffffL);
            exploded2compactBundle(baseRow, baseCol, e.getValue(), cLayerPath);
        }
    }

    /**
     * 松散缓存根目录 -> 紧凑缓存根目录。
     * 输入 scPath 应包含 _alllayers；输出在 sePath/_alllayers 下生成 Lxx bundle。
     */
    public void exploded2compact(String scPath, String sePath) throws Exception {
        File ePath = new File(scPath);
        if (!ePath.exists() || !ePath.isDirectory()) {
            throw new Exception(String.format(FORMAT_ERROR_ROOT_DIRECTORY, scPath));
        }
        File eLayersPath = new File(ePath, "_alllayers");
        if (!eLayersPath.exists() || !eLayersPath.isDirectory()) {
            throw new Exception(FORMAT_ERROR_LAYERS_DIRECTORY);
        }
        File cPath = new File(sePath);
        if (!cPath.exists() || !cPath.isDirectory()) {
            cPath.mkdirs();
        }
        File cLayersPath = new File(cPath, "_alllayers");
        if (!cLayersPath.exists()) {
            cLayersPath.mkdirs();
        }

        File[] dirLayers = eLayersPath.listFiles(layerFilter);
        if (dirLayers == null) {
            return;
        }
        Arrays.stream(dirLayers).forEach(e -> {
            System.out.println("converting layer " + e.getName() + " ...");
            exploded2compactLayer(e, new File(cLayersPath, e.getName()));
        });
    }

    public static void main(String[] args) throws Exception {
        // 松散瓦片根目录（含 _alllayers）
        String scPath = "D:\\Data\\SXBaseMap2Exploded\\Layer";
        // 紧凑瓦片输出根目录（将在其 _alllayers 下生成 Lxx bundle）
        String sePath = "D:\\Data\\SXBaseMap2CompactV1\\Layer";

        ExplodedToCompactArcGIS ec = new ExplodedToCompactArcGIS();
        System.out.println("begin...");
        ec.exploded2compact(scPath, sePath);
        System.out.println("ok.");
    }
}
