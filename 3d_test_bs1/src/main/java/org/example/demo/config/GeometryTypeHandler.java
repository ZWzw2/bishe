package org.example.demo.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class GeometryTypeHandler extends BaseTypeHandler<Geometry> {

    private static final Logger log = LoggerFactory.getLogger(GeometryTypeHandler.class);
    private static final WKBReader wkbReader = new WKBReader();
    private static final WKBWriter wkbWriter = new WKBWriter(3);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Geometry parameter, JdbcType jdbcType) throws SQLException {
        byte[] wkb = wkbWriter.write(parameter);
        PGobject pgObject = new PGobject();
        pgObject.setType("geometry");
        pgObject.setValue(WKBWriter.toHex(wkb));
        ps.setObject(i, pgObject);
        log.debug("Setting geometry parameter: {}", WKBWriter.toHex(wkb)); // 改为 debug
    }

    @Override
    public Geometry getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object obj = rs.getObject(columnName);
        if (obj == null) {
            log.debug("Geometry column {} is null", columnName); // 改为 debug
            return null;
        }
        if (obj instanceof byte[]) {
            byte[] wkb = (byte[]) obj;
            log.debug("Retrieved byte[] for column {}: {}", columnName, WKBWriter.toHex(wkb)); // 改为 debug
            return parseWKB(wkb);
        } else if (obj instanceof PGobject) {
            PGobject pgObject = (PGobject) obj;
            String wkbHex = pgObject.getValue();
            log.debug("Retrieved PGobject for column {}: {}", columnName, wkbHex); // 改为 debug
            return wkbHex == null ? null : parseWKB(WKBReader.hexToBytes(wkbHex));
        } else {
            log.error("Unsupported type for column {}: {}", columnName, obj.getClass().getName());
            throw new SQLException("Unsupported type for geometry column: " + obj.getClass().getName());
        }
    }

    @Override
    public Geometry getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object obj = rs.getObject(columnIndex);
        if (obj == null) {
            log.debug("Geometry column index {} is null", columnIndex); // 改为 debug
            return null;
        }
        if (obj instanceof byte[]) {
            byte[] wkb = (byte[]) obj;
            log.debug("Retrieved byte[] for column index {}: {}", columnIndex, WKBWriter.toHex(wkb)); // 改为 debug
            return parseWKB(wkb);
        } else if (obj instanceof PGobject) {
            PGobject pgObject = (PGobject) obj;
            String wkbHex = pgObject.getValue();
            log.debug("Retrieved PGobject for column index {}: {}", columnIndex, wkbHex); // 改为 debug
            return wkbHex == null ? null : parseWKB(WKBReader.hexToBytes(wkbHex));
        } else {
            log.error("Unsupported type for column index {}: {}", columnIndex, obj.getClass().getName());
            throw new SQLException("Unsupported type for geometry column: " + obj.getClass().getName());
        }
    }

    @Override
    public Geometry getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object obj = cs.getObject(columnIndex);
        if (obj == null) {
            log.debug("Geometry column index {} is null", columnIndex); // 改为 debug
            return null;
        }
        if (obj instanceof byte[]) {
            byte[] wkb = (byte[]) obj;
            log.debug("Retrieved byte[] for callable statement index {}: {}", columnIndex, WKBWriter.toHex(wkb)); // 改为 debug
            return parseWKB(wkb);
        } else if (obj instanceof PGobject) {
            PGobject pgObject = (PGobject) obj;
            String wkbHex = pgObject.getValue();
            log.debug("Retrieved PGobject for callable statement index {}: {}", columnIndex, wkbHex); // 改为 debug
            return wkbHex == null ? null : parseWKB(WKBReader.hexToBytes(wkbHex));
        } else {
            log.error("Unsupported type for callable statement index {}: {}", columnIndex, obj.getClass().getName());
            throw new SQLException("Unsupported type for geometry column: " + obj.getClass().getName());
        }
    }

    private Geometry parseWKB(byte[] wkb) throws SQLException {
        try {
            Geometry geometry = wkbReader.read(wkb);
            log.debug("Parsed WKB to Geometry: {}", geometry.toText()); // 改为 debug
            return geometry;
        } catch (Exception e) {
            log.error("Failed to parse WKB: {}", WKBWriter.toHex(wkb), e);
            throw new SQLException("Failed to parse WKB: " + WKBWriter.toHex(wkb), e);
        }
    }
}