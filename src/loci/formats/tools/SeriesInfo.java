/*
 * #%L
 * Bio-Formats command line tools for reading and converting files
 * %%
 * Copyright (C) 2005 - 2017 Open Microscopy Environment:
 *   - Board of Regents of the University of Wisconsin-Madison
 *   - Glencoe Software, Inc.
 *   - University of Dundee
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package loci.formats.tools;

import java.io.IOException;
import java.io.File;
import java.util.Hashtable;

import loci.common.ByteArrayHandle;
import loci.common.DebugTools;
import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.services.ServiceException;
import loci.formats.*;
import loci.formats.in.DynamicMetadataOptions;
import loci.formats.in.MetadataLevel;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.JSONObject;
import org.json.JSONArray;

import com.google.common.collect.ImmutableSet;

/**
 * SeriesInfo is a utility class for reading a file
 * and reporting information about it.
 */
public class SeriesInfo {

    // -- Constants --

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageInfo.class);
    private static final String NEWLINE = System.getProperty("line.separator");

    private static final ImmutableSet<String> HELP_ARGUMENTS =
            ImmutableSet.of("-h", "-help", "--help");

    // -- Fields --

    private String id = null;
    private boolean printVersion = false;
    private boolean cache = false;
    private boolean preload = false;
    private boolean usedFiles = true;
    private String map = null;
    private String format = null;
    private String cachedir = null;
    private DynamicMetadataOptions options = new DynamicMetadataOptions();

    private IFormatReader reader;
    private IFormatReader baseReader;

    // -- ImageInfo methods --

    public boolean parseArgs(String[] args) {
        for (int i=0; i<args.length; i++) {
            if (args[i].startsWith("-")) {
            } else {
                if (id == null) id = args[i];
            }
        }
        return true;
    }

    public void printUsage() {
        String[] s = {
                "Exports series name from file",
                ""
        };
        for (int i=0; i<s.length; i++) LOGGER.info(s[i]);
    }

    public void setReader(IFormatReader reader) {
        this.reader = reader;
    }

    public void createReader() {
        if (reader != null) return; // reader was set programmatically
        if (format != null) {
            // create reader of a specific format type
            try {
                Class<?> c = Class.forName("loci.formats.in." + format + "Reader");
                reader = (IFormatReader) c.newInstance();
            }
            catch (ClassNotFoundException exc) {
                LOGGER.warn("Unknown reader: {}", format);
                LOGGER.debug("", exc);
            }
            catch (InstantiationException exc) {
                LOGGER.warn("Cannot instantiate reader: {}", format);
                LOGGER.debug("", exc);
            }
            catch (IllegalAccessException exc) {
                LOGGER.warn("Cannot access reader: {}", format);
                LOGGER.debug("", exc);
            }
        }
        if (reader == null) reader = new ImageReader();
        baseReader = reader;
    }

    public void mapLocation() throws IOException {
        if (map != null) Location.mapId(id, map);
        else if (preload) {
            RandomAccessInputStream f = new RandomAccessInputStream(id);
            if (!(reader instanceof ImageReader)) {
                // verify format
                LOGGER.info("Checking {} format [{}]", reader.getFormat(),
                        reader.isThisType(f) ? "yes" : "no");
                f.seek(0);
            }
            int len = (int) f.length();
            LOGGER.info("Caching {} bytes:", len);
            byte[] b = new byte[len];
            int blockSize = 8 * 1024 * 1024; // 8 MB
            int read = 0, left = len;
            while (left > 0) {
                int r = f.read(b, read, blockSize < left ? blockSize : left);
                read += r;
                left -= r;
                float ratio = (float) read / len;
                int p = (int) (100 * ratio);
                LOGGER.info("\tRead {} bytes ({}% complete)", read, p);
            }
            f.close();
            ByteArrayHandle file = new ByteArrayHandle(b);
            Location.mapFile(id, file);
        }
    }

    public void configureReaderPreInit() throws FormatException, IOException {
        // check file format
        if (reader instanceof ImageReader) {
            // determine format
            ImageReader ir = (ImageReader) reader;
            if (new Location(id).exists()) {
                LOGGER.info("Checking file format [{}]", ir.getFormat(id));
            }
        }
        else {
            // verify format
            LOGGER.info("Checking {} format [{}]", reader.getFormat(),
                    reader.isThisType(id) ? "yes" : "no");
        }

        LOGGER.info("Initializing reader");
        if (cache) {
            if (cachedir != null) {
                reader  = new Memoizer(reader, 0, new File(cachedir));
            } else {
                reader = new Memoizer(reader, 0);
            }
        }

        reader.close();
        options.setMetadataLevel(MetadataLevel.ALL);
        reader.setMetadataOptions(options);
    }

    public void readSeriesInfo() throws FormatException, IOException {

        // read basic metadata
        LOGGER.info("");
        LOGGER.info("Reading core metadata");
        if (map != null) LOGGER.info("Mapped filename = {}", map);
        if (usedFiles) {
            String[] used = reader.getUsedFiles();
            boolean usedValid = used != null && used.length > 0;
            if (usedValid) {
                for (int u=0; u<used.length; u++) {
                    if (used[u] == null) {
                        usedValid = false;
                        break;
                    }
                }
            }
            if (!usedValid) {
                LOGGER.warn("************ invalid used files list ************");
            }
            if (used == null) {
                LOGGER.info("Used files = null");
            }
            else if (used.length == 0) {
                LOGGER.info("Used files = []");
            }
            else if (used.length > 1) {
                LOGGER.info("Used files:");
                for (int u=0; u<used.length; u++) LOGGER.info("\t{}", used[u]);
            }
            else if (!id.equals(used[0])) {
                LOGGER.info("Used files = [{}]", used[0]);
            }
        }

        JSONArray ja = new JSONArray();

        int seriesCount = reader.getSeriesCount();

        for (int j=0; j<seriesCount; j++) {
            reader.setSeries(j);
            Hashtable<String, Object> meta = reader.getSeriesMetadata();

            // read basic metadata for series #i
            String seriesName = (meta.containsKey("Image name")) ? meta.get("Image name").toString() : "Series " + Integer.toString(j);
            int sizeX = reader.getSizeX();
            int sizeY = reader.getSizeY();
            int sizeZ = reader.getSizeZ();
            int sizeC = reader.getSizeC();
            int sizeT = reader.getSizeT();

            JSONObject series = new JSONObject();
            try {
                series.put("Name", seriesName);
                series.put("SizeX", sizeX);
                series.put("SizeY", sizeY);
                series.put("SizeZ", sizeZ);
                series.put("SizeC", sizeC);
                series.put("SizeT", sizeT);
                ja.put(series);
            } catch ( JSONException e) {
                LOGGER.info("JSON Exception: {}", e.toString());
            }
        }

        System.out.print(ja.toString());
    }

    /**
     * A utility method for reading a file from the command line,
     * and displaying the results in a simple display.
     */
    public boolean getInfo(String[] args)
            throws FormatException, ServiceException, IOException {

        for (final String arg : args) {
            if (HELP_ARGUMENTS.contains(arg)) {
                if (reader == null) {
                    reader = new ImageReader();
                }
                printUsage();
                return false;
            }
        }
        boolean validArgs = parseArgs(args);
        if (!validArgs) return false;
        if (printVersion) {
            CommandLineTools.printVersion();
            return true;
        }

        createReader();

        if (id == null) {
            printUsage();
            return false;
        }

        mapLocation();
        configureReaderPreInit();

        // initialize reader
        long s = System.currentTimeMillis();
        try {
            reader.setId(id);
        } catch (FormatException exc) {
            reader.close();
            LOGGER.error("Failure during the reader initialization");
            LOGGER.debug("", exc);
            return false;
        }
        long e = System.currentTimeMillis();
        float sec = (e - s) / 1000f;
        LOGGER.info("Initialization took {}s", sec);

        readSeriesInfo();
        reader.close();

        return true;
    }

    // -- Main method --

    public static void main(String[] args) throws Exception {
        DebugTools.enableLogging("OFF");
        if (!new SeriesInfo().getInfo(args)) System.exit(1);
    }

}
