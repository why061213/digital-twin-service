package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProvinceCodeResolver {

    private final Map<String, String> nameToCode = new LinkedHashMap<>();
    private final Map<String, String> codeToShortName = new LinkedHashMap<>();

    public ProvinceCodeResolver() {
        register("110000", "北京", "北京市");
        register("120000", "天津", "天津市");
        register("130000", "河北", "河北省");
        register("140000", "山西", "山西省");
        register("150000", "内蒙古", "内蒙古自治区");
        register("210000", "辽宁", "辽宁省");
        register("220000", "吉林", "吉林省");
        register("230000", "黑龙江", "黑龙江省");
        register("310000", "上海", "上海市");
        register("320000", "江苏", "江苏省");
        register("330000", "浙江", "浙江省");
        register("340000", "安徽", "安徽省");
        register("350000", "福建", "福建省");
        register("360000", "江西", "江西省");
        register("370000", "山东", "山东省");
        register("410000", "河南", "河南省");
        register("420000", "湖北", "湖北省");
        register("430000", "湖南", "湖南省");
        register("440000", "广东", "广东省");
        register("450000", "广西", "广西壮族自治区");
        register("460000", "海南", "海南省");
        register("500000", "重庆", "重庆市");
        register("510000", "四川", "四川省");
        register("520000", "贵州", "贵州省");
        register("530000", "云南", "云南省");
        register("540000", "西藏", "西藏自治区");
        register("610000", "陕西", "陕西省");
        register("620000", "甘肃", "甘肃省");
        register("630000", "青海", "青海省");
        register("640000", "宁夏", "宁夏回族自治区");
        register("650000", "新疆", "新疆维吾尔自治区");
        register("710000", "台湾", "台湾省");
        register("810000", "香港", "香港特别行政区");
        register("820000", "澳门", "澳门特别行政区");
    }

    private void register(String code, String shortName, String fullName) {
        codeToShortName.put(code, shortName);
        nameToCode.put(shortName, code);
        nameToCode.put(fullName, code);
    }

    public String provinceKey(ExternalOrderRecord.Location location) {
        if (location == null) return "";

        String adcode = safe(location.adcode());
        if (adcode.length() >= 2) {
            return adcode.substring(0, 2) + "0000";
        }

        String province = safe(location.province());
        if (!province.isBlank()) {
            return nameToCode.getOrDefault(province, province);
        }

        return "";
    }

    public String shortName(String provinceKey) {
        if (provinceKey == null || provinceKey.isBlank()) return "";
        return codeToShortName.getOrDefault(provinceKey, provinceKey);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}