package com.heli.gis;

import java.io.*;
import java.util.Arrays;
import java.util.stream.Stream;

public class CompactToExploded {
    private static String _BUNDLE_DATA = "bundle";
    private static String _BUNDLE_DATA_SUBFFIX = "." + _BUNDLE_DATA;
    private static String _BUNDLE_IDX_SUBFFIX = ".bundlx";
    private static String FORMAT_ERROR_ROOT_DIRECTORY = "root directory '%s' is not found or it is not Directory.";
    private static String FORMAT_ERROR_LAYERS_DIRECTORY = "_alllayers is not in root directory or it is not Directory.";

    private static int SIZE = 128;
    private static int SIZE0 = SIZE * SIZE;
    private static int FSIZE = SIZE0 * 4;

    private long byteArray2Long(byte [] buffer){
        return (long)(buffer[0]& 0xff) + (long)(buffer[1] & 0xff)
                * 256 + (long)(buffer[2]& 0xff) * 65536
                + (long)(buffer[3] &0xff) * 16777216
                + (long)(buffer[4]& 0xff) * 4294967296L;
    }
    private int byteArray2Integer(byte [] buffer){
        return (int)(buffer[0]& 0xff) + (int)(buffer[1] & 0xff)
                * 256 + (int)(buffer[2]& 0xff) * 65536
                + (int)(buffer[3] &0xff) * 16777216;
    }
    public void compact2explodedBundle(File cLayerPath,String bundle,File eLayerPath)  {
        RandomAccessFile _dataFile = null;
        RandomAccessFile _idxFile = null;
        try{
            File fDataFile = new File(cLayerPath,bundle + _BUNDLE_DATA_SUBFFIX);
            File fIdxFile =  new File(cLayerPath,bundle + _BUNDLE_IDX_SUBFFIX);
            _dataFile = new RandomAccessFile(fDataFile.getPath(),"r");
            _idxFile = new RandomAccessFile(fIdxFile.getPath(),"r");
            String [] rc = bundle.split("C");
            int rn = Integer.valueOf(rc[0].substring(1),16);
            int cn = Integer.valueOf(rc[1],16);

            final RandomAccessFile dataFile = _dataFile;
            final RandomAccessFile idxFile = _idxFile;

            Stream.iterate(0,(x)->x+1).limit(SIZE).forEach(i->{
                String rowName = String.format("R%08x",rn+i);
                File rowDir = new File(eLayerPath,rowName);
                //if(!rowDir.exists())
                //    rowDir.mkdir();

                //System.out.println("==============="+rowDir.getPath());

                Stream.iterate(0,(x)->x+1).limit(SIZE).forEach(j->{
                    int k = j * SIZE + i;
                    int index = 16 + k * 5;

                    //final RandomAccessFile dataFile = final_dataFile;
                    //final RandomAccessFile idxFile = final_idxFile;

                    try {
                        byte [] buffer = new byte[5];
                        idxFile.seek(index);
                        idxFile.read(buffer,0,5);
                        long offset = byteArray2Long(buffer);
                        //if(offset >= 60 + FSIZE){

                            byte [] buffer2 = new byte[4];
                            dataFile.seek(offset);
                            dataFile.read(buffer2,0,4);
                            int length = byteArray2Integer(buffer2);
                            if(length > 0 ) {
                                if(!rowDir.exists())        //delete
                                    rowDir.mkdir();
                                byte[] result = new byte[length];
                                dataFile.read(result, 0, length);
                                byte [] sign = new byte[4];
                                System.arraycopy(result,6,sign,0,4);
                                char [] cSign = "JFIF".toCharArray();
                                String subfixx = "png";
                                if(sign[0] == cSign[0] && sign[1] == cSign[1] && sign[2] == cSign[2] && sign[3] == cSign[3]){
                                    subfixx = "jpg";
                                }
                                String colName = String.format("C%08x.%s",cn+j,subfixx);
                                File colFile = new File(rowDir,colName);
                                if(colFile.exists() && colFile.isFile())
                                    colFile.delete();
                                FileOutputStream fos = null;
                                try {
                                    //System.out.println("---------"+colFile.getPath());
                                    fos = new FileOutputStream(colFile);
                                    fos.write(result);
                                    fos.flush();
                                }finally {
                                    if (fos != null) {
                                        try{
                                            fos.close();
                                        }catch (IOException e) {}
                                    }
                                }

                            }
                        //}
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                });
            });
        }catch (FileNotFoundException e){
            e.printStackTrace();
        }finally {
            if (_dataFile != null) {
                try{
                    _dataFile.close();
                }catch (IOException e) {}
            }
            if (_idxFile != null) {
                try{
                    _idxFile.close();
                }catch (IOException e) {}
            }
        }

    }
    public void compact2explodedLayer(File cLayerPath,File eLayerPath){
        if(!eLayerPath.exists())
            eLayerPath.mkdir();
        String [] bundleFileNames = cLayerPath.list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String [] n = name.split("\\.");
                return (n.length == 2 && n[1].toLowerCase().equals(_BUNDLE_DATA));
            }
        });

        Arrays.stream(bundleFileNames).map(e->e.split("\\.")[0]).forEach(e->
            compact2explodedBundle(cLayerPath,e,eLayerPath)
        );
        /*Arrays.stream(bundleFileNames).parallel().map(e->e.split(".")[0]).forEach(e->
                compact2explodedBundle(e,eLayerPath)
        );*/
    }
    public void compact2exploded(String scPath,String sePath) throws Exception {
        File cPath = new File(scPath);
        if(!cPath.exists() || !cPath.isDirectory())
            throw new Exception(String.format(FORMAT_ERROR_ROOT_DIRECTORY,scPath));
        File cLayersPath = new File(cPath,"_alllayers");
        if(!cLayersPath.exists() || !cLayersPath.isDirectory())
            throw new Exception(FORMAT_ERROR_LAYERS_DIRECTORY);
        File ePath = new File(sePath);

        if(!ePath.exists() || !ePath.isDirectory())
            ePath.mkdir();

        File [] dirLayers = cLayersPath.listFiles(new FilenameFilter(){

            public boolean accept(File dir, String name) {
                char firstLittle = name.charAt(0);
                String lastWord = name.substring(1);
                boolean isNotNumber = lastWord.chars().filter(e-> e < '0' || e > '9').findAny().isPresent();
                return new File(dir,name).isDirectory() && (firstLittle == 'L' || firstLittle == 'l') && !isNotNumber;
            }
        });
        Arrays.stream(dirLayers).forEach(e -> compact2explodedLayer(e,new File(ePath,e.getName())));
        //Arrays.stream(dirLayers).parallel().forEach(e -> compact2explodedLayer(e,new File(ePath,e.getName())));
    }

    public static void main(String[] args) throws Exception {
//        if(args.length != 2) {
//            System.out.println("Help:\n  please input source directory and target directory.");
//            return;
//        }

//        String scPath = args[0];
//        String sePath = args[1];
        String scPath = "D:\\Data\\SXBaseMap2\\Layer";
        String sePath = "D:\\Data\\SXBaseMap2Exploded";
        CompactToExploded ce = new CompactToExploded();
        System.out.println("begin...");
        ce.compact2exploded(scPath,sePath);
        System.out.println("ok.");

        /*int a=123;
        System.out.println(String.format("%08x",123));

        byte [] b = new byte[5];
        System.out.println(b.length);*/
    }
}
