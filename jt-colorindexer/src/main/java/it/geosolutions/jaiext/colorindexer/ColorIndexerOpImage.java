/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2005-2013, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.jaiext.colorindexer;

import it.geosolutions.jaiext.iterators.RandomIterFactory;
import it.geosolutions.jaiext.range.Range;
import it.geosolutions.jaiext.range.RangeFactory;

import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;

import javax.media.jai.ImageLayout;
import javax.media.jai.PlanarImage;
import javax.media.jai.PointOpImage;
import javax.media.jai.ROI;
import javax.media.jai.ROIShape;
import javax.media.jai.iterator.RandomIter;

import com.sun.media.jai.util.ImageUtil;

/**
 * {@link PointOpImage} to perform a color inversion given a certain {@link ColorIndexer}. Derived and improved from GeoTools one
 * 
 * @author Andrea Aime, GeoSolutions
 * 
 * @source $URL$
 */
@SuppressWarnings("unchecked")
public class ColorIndexerOpImage extends PointOpImage {

    /**
     * Constant indicating that the inner random iterators must pre-calculate an array of the image positions
     */
    public static final boolean ARRAY_CALC = true;

    /**
     * Constant indicating that the inner random iterators must cache the current tile position
     */
    public static final boolean TILE_CACHED = true;

    private ColorIndexer palette;

    private IndexColorModel icm;

    private boolean hasNoData;

    private Range nodata;

    private boolean hasROI;

    private ROI roi;

    private Rectangle roiBounds;

    private PlanarImage roiImage;

    private byte destNoData;

    /** Boolean indicating if No Data and ROI are not used */
    private boolean caseA;

    /** Boolean indicating if only the ROI is used */
    private boolean caseB;

    /** Boolean indicating if only the No Data are used */
    private boolean caseC;

    private boolean[] lut;

    public ColorIndexerOpImage(RenderedImage image, ColorIndexer palette, ROI roi, Range nodata,
            int destNoData, RenderingHints hints) {
        super(image, buildLayout(image, palette.toIndexColorModel()), hints, false);
        this.icm = palette.toIndexColorModel();

        this.setSource(image, 0);
        this.palette = palette;

        // Checking for NoData
        hasNoData = nodata != null;
        if (hasNoData) {
            this.nodata = RangeFactory.convertToByteRange(nodata);
            initLookupTable();
        }

        // Checking for ROI
        hasROI = roi != null;
        if (hasROI) {
            this.roi = roi;
            roiBounds = roi.getBounds();
        }

        // Setting the Index for the NoData value
        this.destNoData = (byte) (destNoData & 0xFF);

        // Definition of the possible cases that can be found
        // caseA = no ROI nor No Data
        // caseB = ROI present but No Data not present
        // caseC = No Data present but ROI not present
        // Last case not defined = both ROI and No Data are present
        caseA = !hasROI && !hasNoData;
        caseB = hasROI && !hasNoData;
        caseC = !hasROI && hasNoData;
    }

    /**
     * Creation of a boolean lookup table used for checking if the input samples are NoData
     */
    private void initLookupTable() {
        // Create the LUT
        lut = new boolean[256];
        // Populate it
        for (int i = 0; i < 256; i++) {
            byte b = (byte) i;
            lut[i] = !nodata.contains(b);
        }
    }

    /**
     * Builds an {@code ImageLayout} for this image. The {@code width} and {@code height} arguments are requested tile dimensions which will only be
     * used if they are smaller than this operator's default tile dimension.
     * 
     * @param minX origin X ordinate
     * @param minY origin Y ordinate
     * @param width requested tile width
     * @param height requested tile height
     * @param sm sample model
     * 
     * @return the {@code ImageLayout} object
     */
    static ImageLayout buildLayout(RenderedImage image, IndexColorModel icm) {
        // build a sample model for the single tile
        ImageLayout il = new ImageLayout();
        il.setMinX(image.getMinX());
        il.setMinY(image.getMinY());
        il.setWidth(image.getWidth());
        il.setHeight(image.getHeight());
        il.setColorModel(icm);

        SampleModel sm = icm.createCompatibleSampleModel(image.getWidth(), image.getHeight());
        il.setSampleModel(sm);

        if (!(image instanceof BufferedImage)) {

            il.setTileWidth(image.getTileWidth());
            il.setTileHeight(image.getTileHeight());
            il.setTileGridXOffset(image.getTileGridXOffset());
            il.setTileGridYOffset(image.getTileGridYOffset());
        } else {
            // untiled in case the input image is untiled
            // this could be optimized further by _not_
            // simply forwarding getTile calls but converting coords.
            il.setTileWidth(image.getWidth());
            il.setTileHeight(image.getHeight());
            il.setTileGridXOffset(0);
            il.setTileGridYOffset(0);
        }

        return il;
    }

    @Override
    public Raster computeTile(int tx, int ty) {
        final RenderedImage sourceImage = getSourceImage(0);
        final Raster src = sourceImage.getTile(tx, ty);
        if (src == null) {
            return null;
        }
        final WritableRaster dest = icm.createCompatibleWritableRaster(src.getWidth(),
                src.getHeight()).createWritableTranslatedChild(src.getMinX(), src.getMinY());

        // ROI check
        ROI roiTile = null;

        RandomIter roiIter = null;

        boolean roiContainsTile = false;
        boolean roiDisjointTile = false;

        Rectangle destRect = dest.getBounds();
        // If a ROI is present, then only the part contained inside the current
        // tile bounds is taken.
        if (hasROI) {
            Rectangle srcRectExpanded = mapDestRect(destRect, 0);
            // The tile dimension is extended for avoiding border errors
            srcRectExpanded.setRect(srcRectExpanded.getMinX() - 1, srcRectExpanded.getMinY() - 1,
                    srcRectExpanded.getWidth() + 2, srcRectExpanded.getHeight() + 2);
            roiTile = roi.intersect(new ROIShape(srcRectExpanded));

            if (!roiBounds.intersects(srcRectExpanded)) {
                roiDisjointTile = true;
            } else {
                roiContainsTile = roiTile.contains(srcRectExpanded);
                if (!roiContainsTile) {
                    if (!roiTile.intersects(srcRectExpanded)) {
                        roiDisjointTile = true;
                    } else {
                        PlanarImage roiIMG = getImage();
                        roiIter = RandomIterFactory.create(roiIMG, null, TILE_CACHED, ARRAY_CALC);
                    }
                }
            }
        }

        if (roiDisjointTile) {
            ImageUtil.fillBackground(dest, destRect, new double[] { destNoData });
            return dest;
        }

        final int w = dest.getWidth();
        final int h = dest.getHeight();
        final int srcMinX = Math.max(sourceImage.getMinX(), src.getMinX());
        final int srcMinY = Math.max(sourceImage.getMinY(), src.getMinY());
        final int srcMaxX = Math.min(sourceImage.getMinX() + sourceImage.getWidth(), src.getMinX()
                + w);
        final int srcMaxY = Math.min(sourceImage.getMinY() + sourceImage.getHeight(), src.getMinY()
                + h);
        final int dstMinX = Math.max(src.getMinX(), sourceImage.getMinX());
        final int dstMinY = Math.max(src.getMinY(), sourceImage.getMinY());
        int srcBands = src.getNumBands();
        final int[] pixel = new int[srcBands];
        final byte[] bytes = new byte[srcBands];

        if (caseA || (caseB && roiContainsTile)) {
            for (int y = srcMinY, y_ = dstMinY; y < srcMaxY; y++, y_++) {
                for (int x = srcMinX, x_ = dstMinX; x < srcMaxX; x++, x_++) {
                    src.getPixel(x, y, pixel);
                    for (int i = 0; i < srcBands; i++) {
                        bytes[i] = (byte) (pixel[i] & 0xFF);
                    }

                    int r, g, b, a;

                    if (srcBands == 1 || srcBands == 2) {
                        r = g = b = pixel[0] & 0xFF;
                        a = srcBands == 2 ? pixel[1] & 0xFF : 255;
                    } else {
                        r = pixel[0] & 0xFF;
                        g = pixel[1] & 0xFF;
                        b = pixel[2] & 0xFF;
                        a = srcBands == 4 ? pixel[3] & 0xFF : 255;
                    }

                    int idx = palette.getClosestIndex(r, g, b, a);
                    dest.setSample(x_, y_, 0, (byte) (idx & 0xff));
                }
            }
        } else if (caseB) {
            for (int y = srcMinY, y_ = dstMinY; y < srcMaxY; y++, y_++) {
                for (int x = srcMinX, x_ = dstMinX; x < srcMaxX; x++, x_++) {
                    if (roiBounds.contains(x, y) && roiIter.getSample(x, y, 0) > 0) {
                        src.getPixel(x, y, pixel);
                        for (int i = 0; i < srcBands; i++) {
                            bytes[i] = (byte) (pixel[i] & 0xFF);
                        }

                        int r, g, b, a;

                        if (srcBands == 1 || srcBands == 2) {
                            r = g = b = pixel[0] & 0xFF;
                            a = srcBands == 2 ? pixel[1] & 0xFF : 255;
                        } else {
                            r = pixel[0] & 0xFF;
                            g = pixel[1] & 0xFF;
                            b = pixel[2] & 0xFF;
                            a = srcBands == 4 ? pixel[3] & 0xFF : 255;
                        }

                        int idx = palette.getClosestIndex(r, g, b, a);
                        dest.setSample(x_, y_, 0, (byte) (idx & 0xff));
                    } else {
                        dest.setSample(x_, y_, 0, destNoData);
                    }
                }
            }
        } else if (caseC || (hasROI && hasNoData && roiContainsTile)) {
            for (int y = srcMinY, y_ = dstMinY; y < srcMaxY; y++, y_++) {
                for (int x = srcMinX, x_ = dstMinX; x < srcMaxX; x++, x_++) {
                    src.getPixel(x, y, pixel);
                    boolean valid = true;
                    for (int i = 0; i < srcBands && valid; i++) {
                        int b = pixel[i] & 0xFF;
                        valid &= lut[b];
                        bytes[i] = (byte) (b);
                    }

                    if (valid) {
                        int r, g, b, a;

                        if (srcBands == 1 || srcBands == 2) {
                            r = g = b = pixel[0] & 0xFF;
                            a = srcBands == 2 ? pixel[1] & 0xFF : 255;
                        } else {
                            r = pixel[0] & 0xFF;
                            g = pixel[1] & 0xFF;
                            b = pixel[2] & 0xFF;
                            a = srcBands == 4 ? pixel[3] & 0xFF : 255;
                        }

                        int idx = palette.getClosestIndex(r, g, b, a);
                        dest.setSample(x_, y_, 0, (byte) (idx & 0xff));
                    } else {
                        dest.setSample(x_, y_, 0, destNoData);
                    }
                }
            }
        } else {
            for (int y = srcMinY, y_ = dstMinY; y < srcMaxY; y++, y_++) {
                for (int x = srcMinX, x_ = dstMinX; x < srcMaxX; x++, x_++) {
                    if (roiBounds.contains(x, y) && roiIter.getSample(x, y, 0) > 0) {
                        src.getPixel(x, y, pixel);
                        boolean valid = true;
                        for (int i = 0; i < srcBands; i++) {
                            int b = pixel[i] & 0xFF;
                            valid &= lut[b];
                            bytes[i] = (byte) (b);
                        }

                        if (valid) {
                            int r, g, b, a;

                            if (srcBands == 1 || srcBands == 2) {
                                r = g = b = pixel[0] & 0xFF;
                                a = srcBands == 2 ? pixel[1] & 0xFF : 255;
                            } else {
                                r = pixel[0] & 0xFF;
                                g = pixel[1] & 0xFF;
                                b = pixel[2] & 0xFF;
                                a = srcBands == 4 ? pixel[3] & 0xFF : 255;
                            }

                            int idx = palette.getClosestIndex(r, g, b, a);
                            dest.setSample(x_, y_, 0, (byte) (idx & 0xff));
                        } else {
                            dest.setSample(x_, y_, 0, destNoData);
                        }
                    } else {
                        dest.setSample(x_, y_, 0, destNoData);
                    }
                }
            }
        }

        return dest;
    }

    /**
     * This method provides a lazy initialization of the image associated to the ROI. The method uses the Double-checked locking in order to maintain
     * thread-safety
     * 
     * @return
     */
    private PlanarImage getImage() {
        PlanarImage img = roiImage;
        if (img == null) {
            synchronized (this) {
                img = roiImage;
                if (img == null) {
                    roiImage = img = roi.getAsImage();
                }
            }
        }
        return img;
    }

}