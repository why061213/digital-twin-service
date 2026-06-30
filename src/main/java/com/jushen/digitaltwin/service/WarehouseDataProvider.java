package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.model.WarehouseData;
import java.util.List;

public interface WarehouseDataProvider {
    List<WarehouseData> fetchAllWarehouseData();
}