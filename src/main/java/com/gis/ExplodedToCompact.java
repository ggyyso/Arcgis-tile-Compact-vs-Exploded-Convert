package com.gis;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONUtil;
import com.gis.model.Metadata;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 松散(exploded)瓦片 -> 紧凑(compact/bundle)瓦片 转换程序。
 *
 * 与 {@link CompactToExploded} 互逆：把松散瓦片重新打包为 ArcGIS 10.2 compact v1 缓存
 * （.bundle 数据文件 + .bundlx 索引文件）。
 *
 * 支持两种松散瓦片组织格式（由入口方法/参数 format 区分）：
 *   - "arcgis"：_alllayers/Lxx/R{row-hex}/C{col-hex}.{png|jpg}（ArcGIS 松散缓存默认结构）
 *   - "zxy"   ：{z}/{x}/{y}.{png|jpg}（ZXY 松散瓦片；用户确认 y 为 XYZ 规则、行号从顶部起，
 *              与 ArcGIS 行号方向一致，故 row=y、col=x、level=z 直接一一对应，z 直接作为层号）
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
public class ExplodedToCompact {

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

    // ZXY 层级目录过滤器：纯数字目录（z）
    private final FilenameFilter zDirFilter = (File dir, String name) ->
            new File(dir, name).isDirectory() && isAllDigits(name);

    // ZXY 列目录过滤器：纯数字目录（x = col）
    private final FilenameFilter xDirFilter = (File dir, String name) ->
            new File(dir, name).isDirectory() && isAllDigits(name);

    // ZXY 瓦片文件过滤器：{y}.{ext}，行号部分为纯数字
    private final FilenameFilter yFileFilter = (File dir, String name) -> {
        if (!new File(dir, name).isFile() || name.isEmpty()) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        return isAllDigits(name.substring(0, dot));
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

    /**
     * 瓦片引用：要么指向磁盘文件（流式打包，字节不驻留内存），要么直接持有字节（兼容入口用）。
     * 大数量瓦片场景使用文件引用：收集阶段只保留路径与长度，写入 bundle 时逐个读取、写完即释放。
     */
    private static class TileRef {
        final int k;           // 块内索引 k = j*128 + i
        final File file;       // 文件来源（流式，优先）
        final byte[] data;     // 内存来源（兼容入口）

        TileRef(int k, File file) {
            this.k = k;
            this.file = file;
            this.data = null;
        }

        TileRef(int k, byte[] data) {
            this.k = k;
            this.file = null;
            this.data = data;
        }

        int length() {
            return file != null ? (int) file.length() : data.length;
        }

        byte[] read() throws IOException {
            return file != null ? readFile(file) : data;
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
     * 把一个 128x128 块打包成单个 bundle（流式：瓦片字节逐个读取写入，内存只保留元数据与单个瓦片）。
     * 打包逻辑与 {@link #exploded2compactBundle} 完全一致，仅内存策略不同，输出字节完全相同。
     *
     * @param baseRow    块的基准行（= 行组索引 * 128，即 (row/128)*128）
     * @param baseCol    块的基准列（= (col/128)*128）
     * @param tiles      块内非空瓦片的引用，键为 k=j*128+i（TreeMap 保证按 k 升序），缺省 k 视为空瓦片
     * @param cLayerPath 目标紧凑层目录（_alllayers/Lxx）
     */
    private void writeBundle(int baseRow, int baseCol, Map<Integer, TileRef> tiles, File cLayerPath) {
        String bundle = String.format("R%04xC%04x", baseRow, baseCol);
        File fDataFile = new File(cLayerPath, bundle + _BUNDLE_DATA_SUFFIX);
        File fIdxFile = new File(cLayerPath, bundle + _BUNDLE_IDX_SUFFIX);

        int nonEmpty = tiles.size();

        // 第一遍（只取元数据，不读瓦片字节）：计算每个瓦片的偏移、文件总大小、最大瓦片长度
        long[] offset = new long[SIZE0];
        for (int k = 0; k < SIZE0; k++) {
            offset[k] = DATA_HEADER_SIZE + (long) k * 4;        // 空瓦片指向槽位区，读出长度 0
        }
        long dataPos = DATA_HEADER_SIZE + (long) SLOT_AREA_BYTES;   // 数据区起始 = 65596
        int maxTileLen = 0;                                     // 最大瓦片数据长度
        for (Map.Entry<Integer, TileRef> e : tiles.entrySet()) {
            int k = e.getKey();
            int len = e.getValue().length();
            offset[k] = dataPos;
            dataPos += 4L + len;
            if (len > maxTileLen) {
                maxTileLen = len;
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

            // 数据区：按 k 升序逐个读取瓦片文件写入 [4字节长度][数据]，写入后该瓦片字节即释放
            for (Map.Entry<Integer, TileRef> e : tiles.entrySet()) {
                byte[] data = e.getValue().read();
                writeIntLE(dos, data.length);
                dos.write(data);
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
     * 把一个 128x128 块内的松散瓦片打包成单个 bundle（兼容入口：瓦片字节已驻留内存时使用）。
     *
     * @param baseRow    块的基准行（= 行组索引 * 128，即 (row/128)*128）
     * @param baseCol    块的基准列（= (col/128)*128）
     * @param tiles      键为 k=j*128+i 的瓦片数据（k 由块内行 i、列 j 决定），缺省为空瓦片
     * @param cLayerPath 目标紧凑层目录（_alllayers/Lxx）
     */
    public void exploded2compactBundle(int baseRow, int baseCol, Map<Integer, byte[]> tiles, File cLayerPath) {
        Map<Integer, TileRef> refs = new TreeMap<>();
        for (Map.Entry<Integer, byte[]> e : tiles.entrySet()) {
            refs.put(e.getKey(), new TileRef(e.getKey(), e.getValue()));
        }
        writeBundle(baseRow, baseCol, refs, cLayerPath);
    }

    /**
     * 把一层（_alllayers/Lxx）内的所有松散瓦片按 128x128 块分组并打包成 bundle。
     * 大数量瓦片场景内存友好：只收集瓦片文件引用（不读字节），逐块流式打包。
     *
     * @param eLayerPath 松散层目录
     * @param cLayerPath 目标紧凑层目录
     */
    public void exploded2compactLayer(File eLayerPath, File cLayerPath) {
        if (!cLayerPath.exists()) {
            cLayerPath.mkdir();
        }

        // 键 = (baseRow << 32) | baseCol；值为该块内瓦片引用（TreeMap 保证按 k 升序）
        Map<Long, TreeMap<Integer, TileRef>> bundleMap = new HashMap<>();

        File[] rowDirs = eLayerPath.listFiles(rowDirFilter);
        if (rowDirs == null) {
            return;
        }
        for (File rowDir : rowDirs) {
            System.out.println("Processing row: " + rowDir.getName());
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
                bundleMap.computeIfAbsent(key, x -> new TreeMap<>()).put(k, new TileRef(k, colFile));
            }
        }

        for (Map.Entry<Long, TreeMap<Integer, TileRef>> e : bundleMap.entrySet()) {
            long key = e.getKey();
            int baseRow = (int) (key >> 32);
            int baseCol = (int) (key & 0xffffffffL);
            writeBundle(baseRow, baseCol, e.getValue(), cLayerPath);
        }
    }

    /**
     * 把一层 ZXY 松散瓦片（{z}/{x}/{y}.ext）按 128x128 块分组并打包成 bundle。
     * 映射：row = y、col = x（用户确认 ZXY 为 XYZ 规则，行号从顶部/北起，与 ArcGIS 行号方向一致）。
     * 大数量瓦片场景内存友好：只收集瓦片文件引用（不读字节），逐块流式打包。
     *
     * @param zDir        ZXY 某一层目录（{z}）
     * @param cLayerPath  目标紧凑层目录（_alllayers/L{level}）
     */
    public void exploded2compactLayerZ(File zDir, File cLayerPath) {
        if (!cLayerPath.exists()) {
            cLayerPath.mkdir();
        }

        // 键 = (baseRow << 32) | baseCol；值为该块内瓦片引用（TreeMap 保证按 k 升序）
        Map<Long, TreeMap<Integer, TileRef>> bundleMap = new HashMap<>();

        File[] xDirs = zDir.listFiles(xDirFilter);
        if (xDirs == null) {
            return;
        }
        for (File xDir : xDirs) {
            System.out.println("Processing col: " + xDir.getName());
            long col = Long.parseLong(xDir.getName());            // col = x
            File[] yFiles = xDir.listFiles(yFileFilter);
            if (yFiles == null) {
                continue;
            }
            for (File yFile : yFiles) {
                String name = yFile.getName();
                int dot = name.lastIndexOf('.');
                long row = Long.parseLong(name.substring(0, dot)); // row = y

                int baseRow = (int) ((row / SIZE) * SIZE);       // 块基准行
                int baseCol = (int) ((col / SIZE) * SIZE);       // 块基准列
                int i = (int) (row - baseRow);                   // 块内行 0..127
                int j = (int) (col - baseCol);                   // 块内列 0..127
                int k = j * SIZE + i;

                long key = ((long) baseRow << 32) | (baseCol & 0xffffffffL);
                bundleMap.computeIfAbsent(key, x -> new TreeMap<>()).put(k, new TileRef(k, yFile));
            }
        }

        for (Map.Entry<Long, TreeMap<Integer, TileRef>> e : bundleMap.entrySet()) {
            long key = e.getKey();
            int baseRow = (int) (key >> 32);
            int baseCol = (int) (key & 0xffffffffL);
            writeBundle(baseRow, baseCol, e.getValue(), cLayerPath);
        }
    }

    /**
     * 把 ZXY 松散瓦片根目录（{z}/{x}/{y}.ext）整体转换为紧凑缓存。
     * 每个 z 层直接对应一个 ArcGIS 层目录 L{level}（z 一一对应 level）。
     *
     * @param zxyRoot      ZXY 松散瓦片根目录
     * @param cLayersPath  目标 _alllayers 目录
     */
    public void exploded2compactZXY(File zxyRoot, File cLayersPath) {
        File[] zDirs = zxyRoot.listFiles(zDirFilter);
        if (zDirs == null) {
            return;
        }
        Arrays.sort(zDirs, (a, b) -> Integer.compare(Integer.parseInt(a.getName()), Integer.parseInt(b.getName())));
        for (File zDir : zDirs) {
            System.out.println("Processing z: " + zDir.getName());
            int z = Integer.parseInt(zDir.getName());

            String layerName = String.format("L%02d", z);         // z -> 层号
            System.out.println("converting layer " + layerName + " (z=" + z + ") ...");
            exploded2compactLayerZ(zDir, new File(cLayersPath, layerName));
        }
    }

    /**
     * 松散缓存根目录 -> 紧凑缓存根目录（默认 arcgis 格式）。
     * 输入 scPath 应包含 _alllayers；输出在 sePath/_alllayers 下生成 Lxx bundle。
     */
    public void exploded2compact(String scPath, String sePath) throws Exception {
        exploded2compact(scPath, sePath, "arcgis");
    }

    /**
     * 松散缓存根目录 -> 紧凑缓存根目录。
     *
     * @param scPath 松散瓦片根目录
     * @param sePath 紧凑瓦片输出根目录（将在其 _alllayers 下生成 Lxx bundle）
     * @param format 松散瓦片格式："arcgis"（_alllayers/Lxx/R..C..）或 "zxy"（{z}/{x}/{y}）
     */
    public void exploded2compact(String scPath, String sePath, String format) throws Exception {
        File ePath = new File(scPath);
        if (!ePath.exists() || !ePath.isDirectory()) {
            throw new Exception(String.format(FORMAT_ERROR_ROOT_DIRECTORY, scPath));
        }
        File cPath = new File(sePath);
        if (!cPath.exists() || !cPath.isDirectory()) {
            cPath.mkdirs();
        }
        File cLayersPath = new File(cPath, "_alllayers");
        if (!cLayersPath.exists()) {
            cLayersPath.mkdirs();
        }

        if ("zxy".equalsIgnoreCase(format)) {
            exploded2compactZXY(ePath, cLayersPath);
            return;
        }
        if (!"arcgis".equalsIgnoreCase(format)) {
            throw new Exception("unsupported format: " + format + " (only 'arcgis' or 'zxy')");
        }
        File eLayersPath = new File(ePath, "_alllayers");
        if (!eLayersPath.exists() || !eLayersPath.isDirectory()) {
            throw new Exception(FORMAT_ERROR_LAYERS_DIRECTORY);
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

    private static String cdi="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<EnvelopeN " +
            "xsi:type=\"typens:EnvelopeN\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xmlns:xxs=\"http://www.w3.org/2001/XMLSchema\" xmlns:typens=\"http://www.esri.com/schemas/ArcGIS/10" +
            ".1\">\n" + "  <XMin>${xmin}</XMin>\n" + "  <YMin>${ymin}</YMin>\n" + "  <XMax>${xmax}</XMax>\n" + "  <YMax>${ymax}</YMax>\n" + "  <SpatialReference xsi:type=\"typens:ProjectedCoordinateSystem\">\n" + "    <WKID>102100</WKID>\n" + "    <LatestWKID>3857</LatestWKID>\n" + "  </SpatialReference>\n" + "</EnvelopeN>\n";
    public static void main(String[] args) throws Exception {
        // 第一个参数：松散瓦片格式 "arcgis"（默认）或 "zxy"；
        // 可选第 2、3 个参数：输入根目录、输出根目录（不传则用下方默认路径）
        String format =args.length > 0 ? args[0] : "arcgis";
        String scPath, sePath;
        if ("zxy".equalsIgnoreCase(format)) {
            scPath = args.length > 1 ? args[1] : "Z:\\110\\terrarium\\3857";
            scPath = args.length > 1 ? args[1] : "D:\\Data\\zxyTile";
            sePath = args.length > 2 ? args[2] : "D:\\Data\\zxyTileCompact2\\Layer";
        } else {
            scPath = args.length > 1 ? args[1] : "D:\\Data\\SXBaseMap2Exploded\\Layer";
            sePath = args.length > 2 ? args[2] : "D:\\Data\\SXBaseMap2CompactV1\\Layer";
        }
        System.out.println("scPath=" + scPath + ", sePath=" + sePath);
        ExplodedToCompact ec = new ExplodedToCompact();
        //开始时间
        long begin = System.currentTimeMillis();
        System.out.println("begin... format=" + format);
        ec.exploded2compact(scPath, sePath, format);
        System.out.println("ok.");
        //结束耗时换算为分钟或小时
        long end = System.currentTimeMillis();
        long time = end - begin;
        System.out.println("time: " + time / 1000 / 60 / 60 + " hours " + time / 1000 / 60 % 60 + " minutes " + time / 1000 % 60 + " seconds");
        //拷贝当前目录下的Conf.xml到输出文件夹
        File confFile = new File("Conf.xml");
        if (confFile.exists()) {
            File destFile = new File(sePath, "Conf.xml");
            Files.copy(confFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Conf.xml copied to " + destFile.getAbsolutePath());
        }

        File metaJsonFile=new File(scPath,"metadata.json");
        if (metaJsonFile.exists()) {
            String metaJson = FileUtil.readUtf8String(metaJsonFile);
            Metadata metadata = JSONUtil.toBean(metaJson, Metadata.class);
            cdi = cdi.replace("${xmin}", String.valueOf(metadata.getExtent().getXmin()));
            cdi = cdi.replace("${ymin}", String.valueOf(metadata.getExtent().getYmin()));
            cdi = cdi.replace("${xmax}", String.valueOf(metadata.getExtent().getXmax()));
            cdi = cdi.replace("${ymax}", String.valueOf(metadata.getExtent().getYmax()));
        }

        File cdiFile = new File(sePath, "conf.cdi");
        FileUtil.writeUtf8String(cdi, cdiFile);
        System.out.println("conf.cdi written to " + cdiFile.getAbsolutePath());

    }
}
