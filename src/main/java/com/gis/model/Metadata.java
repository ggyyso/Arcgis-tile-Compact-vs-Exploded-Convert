package com.gis.model;

/**
 * @Auther: Jason
 * @Date: 2026/10/16 Fri 16:54
 * @Description:
 */
public class Metadata {

    private Extent extent;
    private Extent wgs84Extent;

    public Extent getWgs84Extent() {
        return wgs84Extent;
    }

    public void setWgs84Extent(Extent wgs84Extent) {
        this.wgs84Extent = wgs84Extent;
    }

    public Extent getExtent() {
        return extent;
    }

    public void setExtent(Extent extent) {
        this.extent = extent;
    }
}
