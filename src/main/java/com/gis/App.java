package com.gis;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONUtil;
import com.gis.model.Metadata;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * @Auther: Jason
 * @Date: 2026/10/16 Fri 17:33
 * @Description:
 */
public class App {

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
