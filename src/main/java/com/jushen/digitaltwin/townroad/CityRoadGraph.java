package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 市级路网图：节点为城市 adcode，边权重默认为 1。
 * 同省内城市全连接，跨省仅连接边界城市（手工标注）。
 */
@Component
public class CityRoadGraph {

    private final Map<String, List<Edge>> graph = new LinkedHashMap<>();
    private final Map<String, CityInfo> cityInfo = new LinkedHashMap<>();
    private final Map<String, String> cityNameToCode = new LinkedHashMap<>();
    private final Map<String, String> provinceCityNameToCode = new LinkedHashMap<>();
    private final Map<String, List<String>> provinceToCities = new LinkedHashMap<>();
    private final Map<String, String> provinceNames = new LinkedHashMap<>();

    public CityRoadGraph(ProvinceCodeResolver provinceResolver) {
        initCityData();
        buildIntraProvinceLinks();
        buildInterProvinceLinks();
    }

    public List<String> shortestPath(String startCityCode, String targetCityCode) {
        return shortestPath(startCityCode, targetCityCode, List.of(), Set.of());
    }

    public List<String> shortestPath(String startCityCode, String targetCityCode, List<String> preferredProvincePath) {
        return shortestPath(startCityCode, targetCityCode, preferredProvincePath, Set.of());
    }

    public List<String> shortestPath(
            String startCityCode,
            String targetCityCode,
            List<String> preferredProvincePath,
            Set<String> allowedProvinceCodes
    ) {
        if (isBlank(startCityCode) || isBlank(targetCityCode)) return List.of();
        if (startCityCode.equals(targetCityCode)) return List.of(startCityCode);
        if (!graph.containsKey(startCityCode) || !graph.containsKey(targetCityCode)) return List.of();

        Map<String, Integer> preferredProvinceIndex = new HashMap<>();
        for (int i = 0; preferredProvincePath != null && i < preferredProvincePath.size(); i++) {
            preferredProvinceIndex.putIfAbsent(preferredProvincePath.get(i), i);
        }

        Map<String, Integer> distance = new HashMap<>();
        Map<String, Integer> guideScore = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<PathNode> queue = new PriorityQueue<>(
                Comparator.comparingInt(PathNode::cost)
                        .thenComparingInt(PathNode::guideScore)
                        .thenComparing(PathNode::cityCode)
        );

        distance.put(startCityCode, 0);
        guideScore.put(startCityCode, 0);
        queue.add(new PathNode(startCityCode, 0, 0));

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.cost() > distance.getOrDefault(current.cityCode(), Integer.MAX_VALUE)) {
                continue;
            }
            if (current.cityCode().equals(targetCityCode)) {
                break;
            }

            List<Edge> neighbors = new ArrayList<>(graph.getOrDefault(current.cityCode(), List.of()));
            neighbors.sort(Comparator.comparing(Edge::to));
            for (Edge edge : neighbors) {
                if (!provinceAllowed(edge.to(), allowedProvinceCodes)) {
                    continue;
                }
                int nextCost = current.cost() + edge.cost();
                int nextGuideScore = current.guideScore()
                        + provinceGuideScore(current.cityCode(), edge.to(), preferredProvinceIndex);
                int knownCost = distance.getOrDefault(edge.to(), Integer.MAX_VALUE);
                int knownGuideScore = guideScore.getOrDefault(edge.to(), Integer.MAX_VALUE);
                if (nextCost > knownCost || (nextCost == knownCost && nextGuideScore >= knownGuideScore)) {
                    continue;
                }
                distance.put(edge.to(), nextCost);
                guideScore.put(edge.to(), nextGuideScore);
                previous.put(edge.to(), current.cityCode());
                queue.add(new PathNode(edge.to(), nextCost, nextGuideScore));
            }
        }

        if (!distance.containsKey(targetCityCode)) return List.of();

        LinkedList<String> path = new LinkedList<>();
        String cursor = targetCityCode;
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = previous.get(cursor);
        }

        return path;
    }

    private boolean provinceAllowed(String cityCode, Set<String> allowedProvinceCodes) {
        return allowedProvinceCodes == null
                || allowedProvinceCodes.isEmpty()
                || allowedProvinceCodes.contains(provinceCode(cityCode));
    }

    private int provinceGuideScore(String fromCityCode, String toCityCode, Map<String, Integer> preferredProvinceIndex) {
        if (preferredProvinceIndex == null || preferredProvinceIndex.isEmpty()) return 0;

        String fromProvince = provinceCode(fromCityCode);
        String toProvince = provinceCode(toCityCode);
        Integer fromIndex = preferredProvinceIndex.get(fromProvince);
        Integer toIndex = preferredProvinceIndex.get(toProvince);

        if (toIndex == null) return 8;
        if (fromIndex == null) return 2;
        if (toIndex < fromIndex) return 4;
        if (toIndex == fromIndex || toIndex == fromIndex + 1) return 0;
        return 1;
    }

    public List<CityPath> candidatePaths(
            String start, String target,
            int maxCityCount, double toleranceRatio, int absoluteSlack, int maxPathCount
    ) {
        if (isBlank(start) || isBlank(target)) return List.of();
        if (start.equals(target)) return List.of(new CityPath(List.of(start), 0));
        if (!graph.containsKey(start) || !graph.containsKey(target)) return List.of();

        int safeMax = Math.max(2, maxCityCount);
        int safePathCount = Math.max(1, maxPathCount);
        List<CityPath> allPaths = new ArrayList<>();
        collect(start, target, safeMax, 0, new ArrayList<>(List.of(start)), new LinkedHashSet<>(Set.of(start)), allPaths);
        if (allPaths.isEmpty()) return List.of();

        allPaths.sort(Comparator.comparingInt(CityPath::cost).thenComparing(CityPath::pathKey));
        int bestCost = allPaths.get(0).cost();
        int allowedByRatio = (int) Math.ceil(bestCost * (1 + Math.max(0, toleranceRatio)));
        int allowedBySlack = bestCost + Math.max(0, absoluteSlack);
        int allowedCost = Math.min(allowedByRatio, allowedBySlack);
        return allPaths.stream().filter(p -> p.cost() <= allowedCost).limit(safePathCount).toList();
    }

    public boolean hasCity(String cityCode) { return graph.containsKey(cityCode); }
    public CityInfo getCityInfo(String cityCode) { return cityInfo.get(cityCode); }
    public String cityName(String cityCode) {
        CityInfo info = cityInfo.get(cityCode);
        return info == null ? cityCode : info.name();
    }
    public String provinceCode(String cityCode) {
        CityInfo info = cityInfo.get(cityCode);
        return info == null ? "" : info.provinceCode();
    }
    public String provinceName(String provinceCode) {
        return provinceNames.getOrDefault(provinceCode, provinceCode);
    }

    public String cityCodeFor(ExternalOrderRecord.Location location) {
        if (location == null) return "";

        String adcode = safe(location.adcode());
        if (adcode.length() >= 6) {
            String direct = adcode.substring(0, 6);
            if (graph.containsKey(direct)) return direct;

            String cityCode = adcode.substring(0, 4) + "00";
            if (graph.containsKey(cityCode)) return cityCode;

            String provinceCode = adcode.substring(0, 2) + "0000";
            if (graph.containsKey(provinceCode)) return provinceCode;
        }

        String province = safe(location.province());
        String city = safe(location.city());
        String name = safe(location.name());
        String candidateName = !city.isBlank() ? city : name;

        if (!province.isBlank() && !candidateName.isBlank()) {
            String byProvince = provinceCityNameToCode.get(province + "|" + candidateName);
            if (byProvince != null) return byProvince;
        }

        if (!candidateName.isBlank()) {
            return cityNameToCode.getOrDefault(candidateName, "");
        }

        return "";
    }

    private void initCityData() {
        String[][] raw = {
            {"110000","北京市","北京市"}, {"120000","天津市","天津市"},
            {"130100","石家庄市","河北省"},{"130200","唐山市","河北省"},{"130300","秦皇岛市","河北省"},{"130400","邯郸市","河北省"},{"130500","邢台市","河北省"},{"130600","保定市","河北省"},{"130700","张家口市","河北省"},{"130800","承德市","河北省"},{"130900","沧州市","河北省"},{"131000","廊坊市","河北省"},{"131100","衡水市","河北省"},
            {"140100","太原市","山西省"},{"140200","大同市","山西省"},{"140300","阳泉市","山西省"},{"140400","长治市","山西省"},{"140500","晋城市","山西省"},{"140600","朔州市","山西省"},{"140700","晋中市","山西省"},{"140800","运城市","山西省"},{"140900","忻州市","山西省"},{"141000","临汾市","山西省"},{"141100","吕梁市","山西省"},
            {"150100","呼和浩特市","内蒙古自治区"},{"150200","包头市","内蒙古自治区"},{"150300","乌海市","内蒙古自治区"},{"150400","赤峰市","内蒙古自治区"},{"150500","通辽市","内蒙古自治区"},{"150600","鄂尔多斯市","内蒙古自治区"},{"150700","呼伦贝尔市","内蒙古自治区"},{"150800","巴彦淖尔市","内蒙古自治区"},{"150900","乌兰察布市","内蒙古自治区"},
            {"210100","沈阳市","辽宁省"},{"210200","大连市","辽宁省"},{"210300","鞍山市","辽宁省"},{"210400","抚顺市","辽宁省"},{"210500","本溪市","辽宁省"},{"210600","丹东市","辽宁省"},{"210700","锦州市","辽宁省"},{"210800","营口市","辽宁省"},{"210900","阜新市","辽宁省"},{"211000","辽阳市","辽宁省"},{"211100","盘锦市","辽宁省"},{"211200","铁岭市","辽宁省"},{"211300","朝阳市","辽宁省"},{"211400","葫芦岛市","辽宁省"},
            {"220100","长春市","吉林省"},{"220200","吉林市","吉林省"},{"220300","四平市","吉林省"},{"220400","辽源市","吉林省"},{"220500","通化市","吉林省"},{"220600","白山市","吉林省"},{"220700","松原市","吉林省"},{"220800","白城市","吉林省"},
            {"230100","哈尔滨市","黑龙江省"},{"230200","齐齐哈尔市","黑龙江省"},{"230300","鸡西市","黑龙江省"},{"230400","鹤岗市","黑龙江省"},{"230500","双鸭山市","黑龙江省"},{"230600","大庆市","黑龙江省"},{"230700","伊春市","黑龙江省"},{"230800","佳木斯市","黑龙江省"},{"230900","七台河市","黑龙江省"},{"231000","牡丹江市","黑龙江省"},{"231100","黑河市","黑龙江省"},{"231200","绥化市","黑龙江省"},
            {"310000","上海市","上海市"},
            {"320100","南京市","江苏省"},{"320200","无锡市","江苏省"},{"320300","徐州市","江苏省"},{"320400","常州市","江苏省"},{"320500","苏州市","江苏省"},{"320600","南通市","江苏省"},{"320700","连云港市","江苏省"},{"320800","淮安市","江苏省"},{"320900","盐城市","江苏省"},{"321000","扬州市","江苏省"},{"321100","镇江市","江苏省"},{"321200","泰州市","江苏省"},{"321300","宿迁市","江苏省"},
            {"330100","杭州市","浙江省"},{"330200","宁波市","浙江省"},{"330300","温州市","浙江省"},{"330400","嘉兴市","浙江省"},{"330500","湖州市","浙江省"},{"330600","绍兴市","浙江省"},{"330700","金华市","浙江省"},{"330800","衢州市","浙江省"},{"330900","舟山市","浙江省"},{"331000","台州市","浙江省"},{"331100","丽水市","浙江省"},
            {"340100","合肥市","安徽省"},{"340200","芜湖市","安徽省"},{"340300","蚌埠市","安徽省"},{"340400","淮南市","安徽省"},{"340500","马鞍山市","安徽省"},{"340600","淮北市","安徽省"},{"340700","铜陵市","安徽省"},{"340800","安庆市","安徽省"},{"341000","黄山市","安徽省"},{"341100","滁州市","安徽省"},{"341200","阜阳市","安徽省"},{"341300","宿州市","安徽省"},{"341500","六安市","安徽省"},{"341600","亳州市","安徽省"},{"341700","池州市","安徽省"},{"341800","宣城市","安徽省"},
            {"350100","福州市","福建省"},{"350200","厦门市","福建省"},{"350300","莆田市","福建省"},{"350400","三明市","福建省"},{"350500","泉州市","福建省"},{"350600","漳州市","福建省"},{"350700","南平市","福建省"},{"350800","龙岩市","福建省"},{"350900","宁德市","福建省"},
            {"360100","南昌市","江西省"},{"360200","景德镇市","江西省"},{"360300","萍乡市","江西省"},{"360400","九江市","江西省"},{"360500","新余市","江西省"},{"360600","鹰潭市","江西省"},{"360700","赣州市","江西省"},{"360800","吉安市","江西省"},{"360900","宜春市","江西省"},{"361000","抚州市","江西省"},{"361100","上饶市","江西省"},
            {"370100","济南市","山东省"},{"370200","青岛市","山东省"},{"370300","淄博市","山东省"},{"370400","枣庄市","山东省"},{"370500","东营市","山东省"},{"370600","烟台市","山东省"},{"370700","潍坊市","山东省"},{"370800","济宁市","山东省"},{"370900","泰安市","山东省"},{"371000","威海市","山东省"},{"371100","日照市","山东省"},{"371300","临沂市","山东省"},{"371400","德州市","山东省"},{"371500","聊城市","山东省"},{"371600","滨州市","山东省"},{"371700","菏泽市","山东省"},
            {"410100","郑州市","河南省"},{"410200","开封市","河南省"},{"410300","洛阳市","河南省"},{"410400","平顶山市","河南省"},{"410500","安阳市","河南省"},{"410600","鹤壁市","河南省"},{"410700","新乡市","河南省"},{"410800","焦作市","河南省"},{"410900","濮阳市","河南省"},{"411000","许昌市","河南省"},{"411100","漯河市","河南省"},{"411200","三门峡市","河南省"},{"411300","南阳市","河南省"},{"411400","商丘市","河南省"},{"411500","信阳市","河南省"},{"411600","周口市","河南省"},{"411700","驻马店市","河南省"},
            {"420100","武汉市","湖北省"},{"420200","黄石市","湖北省"},{"420300","十堰市","湖北省"},{"420500","宜昌市","湖北省"},{"420600","襄阳市","湖北省"},{"420700","鄂州市","湖北省"},{"420800","荆门市","湖北省"},{"420900","孝感市","湖北省"},{"421000","荆州市","湖北省"},{"421100","黄冈市","湖北省"},{"421200","咸宁市","湖北省"},{"421300","随州市","湖北省"},
            {"430100","长沙市","湖南省"},{"430200","株洲市","湖南省"},{"430300","湘潭市","湖南省"},{"430400","衡阳市","湖南省"},{"430500","邵阳市","湖南省"},{"430600","岳阳市","湖南省"},{"430700","常德市","湖南省"},{"430800","张家界市","湖南省"},{"430900","益阳市","湖南省"},{"431000","郴州市","湖南省"},{"431100","永州市","湖南省"},{"431200","怀化市","湖南省"},{"431300","娄底市","湖南省"},
            {"440100","广州市","广东省"},{"440200","韶关市","广东省"},{"440300","深圳市","广东省"},{"440400","珠海市","广东省"},{"440500","汕头市","广东省"},{"440600","佛山市","广东省"},{"440700","江门市","广东省"},{"440800","湛江市","广东省"},{"440900","茂名市","广东省"},{"441200","肇庆市","广东省"},{"441300","惠州市","广东省"},{"441400","梅州市","广东省"},{"441500","汕尾市","广东省"},{"441600","河源市","广东省"},{"441700","阳江市","广东省"},{"441800","清远市","广东省"},{"441900","东莞市","广东省"},{"442000","中山市","广东省"},{"445100","潮州市","广东省"},{"445200","揭阳市","广东省"},{"445300","云浮市","广东省"},
            {"450100","南宁市","广西壮族自治区"},{"450200","柳州市","广西壮族自治区"},{"450300","桂林市","广西壮族自治区"},{"450400","梧州市","广西壮族自治区"},{"450500","北海市","广西壮族自治区"},{"450600","防城港市","广西壮族自治区"},{"450700","钦州市","广西壮族自治区"},{"450800","贵港市","广西壮族自治区"},{"450900","玉林市","广西壮族自治区"},{"451000","百色市","广西壮族自治区"},{"451100","贺州市","广西壮族自治区"},{"451200","河池市","广西壮族自治区"},{"451300","来宾市","广西壮族自治区"},{"451400","崇左市","广西壮族自治区"},
            {"460100","海口市","海南省"},{"460200","三亚市","海南省"},{"460300","三沙市","海南省"},{"460400","儋州市","海南省"},
            {"500000","重庆市","重庆市"},
            {"510100","成都市","四川省"},{"510300","自贡市","四川省"},{"510400","攀枝花市","四川省"},{"510500","泸州市","四川省"},{"510600","德阳市","四川省"},{"510700","绵阳市","四川省"},{"510800","广元市","四川省"},{"510900","遂宁市","四川省"},{"511000","内江市","四川省"},{"511100","乐山市","四川省"},{"511300","南充市","四川省"},{"511400","眉山市","四川省"},{"511500","宜宾市","四川省"},{"511600","广安市","四川省"},{"511700","达州市","四川省"},{"511800","雅安市","四川省"},{"511900","巴中市","四川省"},{"512000","资阳市","四川省"},
            {"520100","贵阳市","贵州省"},{"520200","六盘水市","贵州省"},{"520300","遵义市","贵州省"},{"520400","安顺市","贵州省"},{"520500","毕节市","贵州省"},{"520600","铜仁市","贵州省"},
            {"530100","昆明市","云南省"},{"530300","曲靖市","云南省"},{"530400","玉溪市","云南省"},{"530500","保山市","云南省"},{"530600","昭通市","云南省"},{"530700","丽江市","云南省"},{"530800","普洱市","云南省"},{"530900","临沧市","云南省"},
            {"540100","拉萨市","西藏自治区"},{"540200","日喀则市","西藏自治区"},{"540300","昌都市","西藏自治区"},{"540400","林芝市","西藏自治区"},{"540500","山南市","西藏自治区"},{"540600","那曲市","西藏自治区"},
            {"610100","西安市","陕西省"},{"610200","铜川市","陕西省"},{"610300","宝鸡市","陕西省"},{"610400","咸阳市","陕西省"},{"610500","渭南市","陕西省"},{"610600","延安市","陕西省"},{"610700","汉中市","陕西省"},{"610800","榆林市","陕西省"},{"610900","安康市","陕西省"},{"611000","商洛市","陕西省"},
            {"620100","兰州市","甘肃省"},{"620200","嘉峪关市","甘肃省"},{"620300","金昌市","甘肃省"},{"620400","白银市","甘肃省"},{"620500","天水市","甘肃省"},{"620600","武威市","甘肃省"},{"620700","张掖市","甘肃省"},{"620800","平凉市","甘肃省"},{"620900","酒泉市","甘肃省"},{"621000","庆阳市","甘肃省"},{"621100","定西市","甘肃省"},{"621200","陇南市","甘肃省"},
            {"630100","西宁市","青海省"},{"630200","海东市","青海省"},
            {"640100","银川市","宁夏回族自治区"},{"640200","石嘴山市","宁夏回族自治区"},{"640300","吴忠市","宁夏回族自治区"},{"640400","固原市","宁夏回族自治区"},{"640500","中卫市","宁夏回族自治区"},
            {"650100","乌鲁木齐市","新疆维吾尔自治区"},{"650200","克拉玛依市","新疆维吾尔自治区"},{"650400","吐鲁番市","新疆维吾尔自治区"},{"650500","哈密市","新疆维吾尔自治区"},
        };
        for (String[] row : raw) {
            String code = row[0], name = row[1], provinceName = row[2];
            String provinceCode = code.substring(0, 2) + "0000";
            cityInfo.put(code, new CityInfo(name, provinceCode, provinceName));
            cityNameToCode.putIfAbsent(name, code);
            provinceCityNameToCode.put(provinceName + "|" + name, code);
            provinceToCities.computeIfAbsent(provinceCode, k -> new ArrayList<>()).add(code);
            provinceNames.putIfAbsent(provinceCode, provinceName);
            graph.putIfAbsent(code, new ArrayList<>());
        }
    }

    private void buildIntraProvinceLinks() {
        for (List<String> cities : provinceToCities.values()) {
            for (int i = 0; i < cities.size(); i++)
                for (int j = i + 1; j < cities.size(); j++)
                    link(cities.get(i), cities.get(j), 1);
        }
    }

    /** 跨省连接：仅连接真正在省界上的城市对（手工标注） */
    private void buildInterProvinceLinks() {
        String[][] border = {
            // ▸华北
            {"110000","131000"},{"110000","130700"},{"110000","130800"},{"110000","130600"},
            {"120000","131000"},{"120000","130900"},{"120000","130200"},
            {"130100","140300"},{"130600","140200"},{"130500","140700"},{"130400","140400"},
            {"130700","150900"},{"130800","150400"},
            {"130300","211400"},{"130800","211300"},
            {"130900","371400"},{"131100","371400"},{"130500","371500"},{"130400","371500"},
            {"130400","410500"},
            {"140500","410800"},{"140800","411200"},{"140400","410500"},{"140500","410300"},
            {"140800","610500"},{"141000","610600"},{"141100","610800"},
            {"150400","211300"},{"150500","211200"},
            {"150500","220300"},
            {"150700","230200"},
            {"150600","610800"},
            {"150300","640200"},{"150600","640200"},
            // ▸华东
            {"310000","320500"},{"310000","320600"},{"310000","330400"},
            {"320500","330100"},{"320500","330400"},{"320100","330500"},{"321100","330500"},
            {"320100","341100"},{"320100","340500"},{"320800","341100"},{"321300","341300"},{"320300","341300"},{"320300","340600"},
            {"320300","371300"},{"320700","371100"},
            {"330100","341000"},{"330800","341000"},{"330100","341800"},{"330500","341800"},
            {"330800","350700"},{"331100","350700"},{"331000","350900"},{"330300","350900"},
            {"330800","361100"},{"331100","361100"},
            {"340800","360400"},{"341000","360400"},{"341000","361100"},{"341700","360400"},
            {"341300","370400"},
            {"341600","411400"},{"341300","411400"},{"341200","411500"},{"341200","411600"},{"341500","411500"},
            {"340800","421100"},{"340800","420200"},{"341500","421100"},
            {"350700","361100"},{"350800","360700"},{"350400","360700"},
            {"350800","441400"},{"350600","445100"},
            {"360400","421100"},{"360400","421200"},{"360400","420200"},
            {"360300","430200"},{"360900","430100"},{"360900","430200"},{"360400","430600"},{"360800","430400"},{"360700","431000"},
            {"360700","440200"},{"360700","441600"},{"360700","441400"},
            {"371500","410900"},{"371700","411400"},{"371700","410200"},{"370800","411400"},
            // ▸华中
            {"411300","421300"},{"411300","420600"},{"411500","421100"},{"411500","420900"},
            {"411200","610500"},{"411300","611000"},
            {"421000","430600"},{"420500","430600"},{"420500","430700"},
            {"420500","500000"},
            {"420300","610900"},{"420300","611000"},
            {"431000","440200"},{"431100","441800"},{"431000","441800"},
            {"431100","450300"},{"431200","450300"},{"430500","450300"},
            {"431200","500000"},
            {"431200","520600"},{"430500","520600"},
            // ▸华南
            {"440800","450500"},{"440900","450900"},{"445300","450400"},{"441200","450400"},{"441200","451100"},{"441800","451100"},
            {"440800","460100"},{"450500","460100"},
            // ▸西南
            {"500000","510100"},{"500000","510500"},{"500000","510900"},{"500000","511500"},{"500000","511600"},{"500000","511700"},
            {"500000","520300"},{"500000","520600"},
            {"500000","610900"},
            {"510500","520300"},{"511500","520500"},
            {"510500","530600"},{"511500","530600"},{"510400","530100"},
            {"510100","540100"},{"511800","540100"},
            {"510800","610700"},{"511900","610700"},{"511700","610900"},
            {"510700","621200"},
            {"520500","530600"},{"520200","530300"},
            // ▸西北
            {"610700","621200"},{"610300","620500"},{"610600","621000"},
            {"610800","640300"},{"610400","640400"},
            {"620900","650100"},
            {"620400","640400"},{"620400","640500"},{"621000","640400"},
            {"620100","630100"},
            {"640200","150300"},{"640100","150600"},
            {"630100","540100"},
            {"630100","650100"},{"650100","540100"},
        };
        for (String[] pair : border) {
            if (graph.containsKey(pair[0]) && graph.containsKey(pair[1]))
                link(pair[0], pair[1], 1);
        }
    }

    private void link(String a, String b, int cost) {
        graph.computeIfAbsent(a, k -> new ArrayList<>()).add(new Edge(b, cost));
        graph.computeIfAbsent(b, k -> new ArrayList<>()).add(new Edge(a, cost));
    }

    private void collect(String cur, String target, int max, int cost,
                         List<String> path, Set<String> visited, List<CityPath> result) {
        if (cur.equals(target)) { result.add(new CityPath(List.copyOf(path), cost)); return; }
        if (path.size() >= max) return;
        List<Edge> neighbors = new ArrayList<>(graph.getOrDefault(cur, List.of()));
        neighbors.sort(Comparator.comparing(Edge::to));
        for (Edge e : neighbors) {
            if (visited.contains(e.to)) continue;
            path.add(e.to); visited.add(e.to);
            collect(e.to, target, max, cost + e.cost, path, visited, result);
            visited.remove(e.to); path.remove(path.size() - 1);
        }
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
    private String safe(String s) { return s == null ? "" : s.trim(); }

    private record Edge(String to, int cost) {}
    private record PathNode(String cityCode, int cost, int guideScore) {}
    public record CityPath(List<String> cityCodes, int cost) {
        public String pathKey() { return String.join(">", cityCodes); }
    }
    public record CityInfo(String name, String provinceCode, String provinceName) {}
}
