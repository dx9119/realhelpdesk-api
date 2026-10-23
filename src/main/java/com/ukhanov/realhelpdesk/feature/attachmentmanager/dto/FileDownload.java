package com.ukhanov.realhelpdesk.feature.attachmentmanager.dto;

import java.io.InputStream;

import com.ukhanov.realhelpdesk.core.http.RangeHeader;

public record FileDownload(String fileName, String contentType, long sizeBytes, RangeHeader.Parsed range,
        InputStream content) {

    public boolean isUnsatisfiable() {
        return content == null;
    }

    /** Отдаётся фрагмент (206): в заголовках нужен Content-Range, Content-Length — длина фрагмента. */
    public boolean isPartial() {
        return range.kind() == RangeHeader.Kind.PARTIAL;
    }
}
