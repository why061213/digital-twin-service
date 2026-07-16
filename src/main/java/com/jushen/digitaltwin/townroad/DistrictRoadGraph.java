package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 区县级路网图：节点为区县 adcode，边权重默认为 1。
 * 同城市内区县全连接，跨城市仅连接边界区县。
 * 基于 CityRoadGraph 改造，不修改原有逻辑。
 */
@Component
public class DistrictRoadGraph {

    private final Map<String, List<Edge>> graph = new LinkedHashMap<>();
    /** key=城市adcode, value=该城市下的区县adcode列表 */
    private final Map<String, List<String>> cityToDistricts = new LinkedHashMap<>();
    /** key=区县adcode, value=区县信息 */
    private final Map<String, DistrictInfo> districtInfo = new LinkedHashMap<>();
    /** key=区县名, value=区县adcode */
    private final Map<String, String> nameToCode = new LinkedHashMap<>();

    private final CityRoadGraph cityGraph;

    public DistrictRoadGraph(CityRoadGraph cityGraph) {
        this.cityGraph = cityGraph;
        initDistrictData();
        buildIntraCityLinks();
        buildBoundaryDistrictLinks();
    }

    // ================================================================
    // 最短路径（复用 Dijkstra，与 CityRoadGraph 一致）
    // ================================================================

    public List<String> shortestPath(String startCode, String targetCode) {
        if (isBlank(startCode) || isBlank(targetCode)) return List.of();
        if (startCode.equals(targetCode)) return List.of(startCode);
        if (!graph.containsKey(startCode) || !graph.containsKey(targetCode)) return List.of();

        Map<String, Integer> distance = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<PathNode> queue = new PriorityQueue<>(Comparator.comparingInt(PathNode::cost));

        distance.put(startCode, 0);
        queue.add(new PathNode(startCode, 0));

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.cost() > distance.getOrDefault(current.code(), Integer.MAX_VALUE)) continue;
            if (current.code().equals(targetCode)) break;

            for (Edge edge : graph.getOrDefault(current.code(), List.of())) {
                int nextCost = current.cost() + edge.cost();
                if (nextCost < distance.getOrDefault(edge.to(), Integer.MAX_VALUE)) {
                    distance.put(edge.to(), nextCost);
                    previous.put(edge.to(), current.code());
                    queue.add(new PathNode(edge.to(), nextCost));
                }
            }
        }

        if (!distance.containsKey(targetCode)) return List.of();
        LinkedList<String> path = new LinkedList<>();
        String cursor = targetCode;
        while (cursor != null) { path.addFirst(cursor); cursor = previous.get(cursor); }
        return path;
    }

    // ================================================================
    // 查询
    // ================================================================

    public boolean hasDistrict(String code) { return graph.containsKey(code); }
    public DistrictInfo getDistrictInfo(String code) { return districtInfo.get(code); }
    public String districtName(String code) {
        DistrictInfo info = districtInfo.get(code);
        return info == null ? code : info.name();
    }

    // ================================================================
    // 区县数据初始化（按省份→城市→区县组织）
    // ================================================================

    private void initDistrictData() {
        // ==================== 北京市 ====================
        addDistricts("110000",
            new String[][]{
                {"110101","东城区"},{"110102","西城区"},{"110105","朝阳区"},{"110106","丰台区"},
                {"110107","石景山区"},{"110108","海淀区"},{"110109","门头沟区"},{"110111","房山区"},
                {"110112","通州区"},{"110113","顺义区"},{"110114","昌平区"},{"110115","大兴区"},
                {"110116","怀柔区"},{"110117","平谷区"},{"110118","密云区"},{"110119","延庆区"},
            });

        // ==================== 天津市 ====================
        addDistricts("120000",
            new String[][]{
                {"120101","和平区"},{"120102","河东区"},{"120103","河西区"},{"120104","南开区"},
                {"120105","河北区"},{"120106","红桥区"},{"120110","东丽区"},{"120111","西青区"},
                {"120112","津南区"},{"120113","北辰区"},{"120114","武清区"},{"120115","宝坻区"},
                {"120116","滨海新区"},{"120117","宁河区"},{"120118","静海区"},{"120119","蓟州区"},
            });

        // ==================== 上海市 ====================
        addDistricts("310000",
            new String[][]{
                {"310101","黄浦区"},{"310104","徐汇区"},{"310105","长宁区"},{"310106","静安区"},
                {"310107","普陀区"},{"310109","虹口区"},{"310110","杨浦区"},{"310112","闵行区"},
                {"310113","宝山区"},{"310114","嘉定区"},{"310115","浦东新区"},{"310116","金山区"},
                {"310117","松江区"},{"310118","青浦区"},{"310120","奉贤区"},{"310151","崇明区"},
            });

        // ==================== 重庆市 ====================
        addDistricts("500000",
            new String[][]{
                {"500101","万州区"},{"500102","涪陵区"},{"500103","渝中区"},{"500104","大渡口区"},
                {"500105","江北区"},{"500106","沙坪坝区"},{"500107","九龙坡区"},{"500108","南岸区"},
                {"500109","北碚区"},{"500110","綦江区"},{"500111","大足区"},{"500112","渝北区"},
                {"500113","巴南区"},{"500114","黔江区"},{"500115","长寿区"},{"500116","江津区"},
                {"500117","合川区"},{"500118","永川区"},{"500119","南川区"},{"500120","璧山区"},
                {"500151","铜梁区"},{"500152","潼南区"},{"500153","荣昌区"},{"500154","开州区"},
                {"500155","梁平区"},{"500156","武隆区"},
            });

        // ==================== 广东省 ====================
        addDistricts("440100",
            new String[][]{
                {"440103","荔湾区"},{"440104","越秀区"},{"440105","海珠区"},{"440106","天河区"},
                {"440111","白云区"},{"440112","黄埔区"},{"440113","番禺区"},{"440114","花都区"},
                {"440115","南沙区"},{"440117","从化区"},{"440118","增城区"},
            });
        addDistricts("440300",
            new String[][]{
                {"440303","罗湖区"},{"440304","福田区"},{"440305","南山区"},{"440306","宝安区"},
                {"440307","龙岗区"},{"440308","盐田区"},{"440309","龙华区"},{"440310","坪山区"},
                {"440311","光明区"},
            });
        addDistricts("440600",
            new String[][]{
                {"440604","禅城区"},{"440605","南海区"},{"440606","顺德区"},{"440607","三水区"},
                {"440608","高明区"},
            });
        addDistricts("441900", new String[][]{{"441900","东莞市"}});
        addDistricts("442000", new String[][]{{"442000","中山市"}});

        // ==================== 浙江省 ====================
        addDistricts("330100",
            new String[][]{
                {"330102","上城区"},{"330105","拱墅区"},{"330106","西湖区"},{"330108","滨江区"},
                {"330109","萧山区"},{"330110","余杭区"},{"330111","富阳区"},{"330112","临安区"},
                {"330113","临平区"},{"330114","钱塘区"},{"330122","桐庐县"},{"330127","淳安县"},
                {"330182","建德市"},
            });
        addDistricts("330200",
            new String[][]{
                {"330203","海曙区"},{"330205","江北区"},{"330206","北仑区"},{"330211","镇海区"},
                {"330212","鄞州区"},{"330213","奉化区"},{"330225","象山县"},{"330226","宁海县"},
                {"330281","余姚市"},{"330282","慈溪市"},
            });

        // ==================== 江苏省 ====================
        addDistricts("320100",
            new String[][]{
                {"320102","玄武区"},{"320104","秦淮区"},{"320105","建邺区"},{"320106","鼓楼区"},
                {"320111","浦口区"},{"320113","栖霞区"},{"320114","雨花台区"},{"320115","江宁区"},
                {"320116","六合区"},{"320117","溧水区"},{"320118","高淳区"},
            });
        addDistricts("320500",
            new String[][]{
                {"320505","虎丘区"},{"320506","吴中区"},{"320507","相城区"},{"320508","姑苏区"},
                {"320509","吴江区"},{"320581","常熟市"},{"320582","张家港市"},{"320583","昆山市"},
                {"320585","太仓市"},
            });
        addDistricts("320200",
            new String[][]{
                {"320205","锡山区"},{"320206","惠山区"},{"320211","滨湖区"},{"320213","梁溪区"},
                {"320214","新吴区"},{"320281","江阴市"},{"320282","宜兴市"},
            });

        // ==================== 福建省 ====================
        addDistricts("350100",
            new String[][]{
                {"350102","鼓楼区"},{"350103","台江区"},{"350104","仓山区"},{"350105","马尾区"},
                {"350111","晋安区"},{"350112","长乐区"},{"350121","闽侯县"},{"350122","连江县"},
                {"350123","罗源县"},{"350124","闽清县"},{"350125","永泰县"},{"350128","平潭县"},
                {"350181","福清市"},
            });
        addDistricts("350200",
            new String[][]{
                {"350203","思明区"},{"350205","海沧区"},{"350206","湖里区"},{"350211","集美区"},
                {"350212","同安区"},{"350213","翔安区"},
            });
        addDistricts("350500",
            new String[][]{
                {"350502","鲤城区"},{"350503","丰泽区"},{"350504","洛江区"},{"350505","泉港区"},
                {"350521","惠安县"},{"350524","安溪县"},{"350525","永春县"},{"350526","德化县"},
                {"350527","金门县"},{"350581","石狮市"},{"350582","晋江市"},{"350583","南安市"},
            });

        // ==================== 湖北省 ====================
        addDistricts("420100",
            new String[][]{
                {"420102","江岸区"},{"420103","江汉区"},{"420104","硚口区"},{"420105","汉阳区"},
                {"420106","武昌区"},{"420107","青山区"},{"420111","洪山区"},{"420112","东西湖区"},
                {"420113","汉南区"},{"420114","蔡甸区"},{"420115","江夏区"},{"420116","黄陂区"},
                {"420117","新洲区"},
            });

        // ==================== 湖南省 ====================
        addDistricts("430100",
            new String[][]{
                {"430102","芙蓉区"},{"430103","天心区"},{"430104","岳麓区"},{"430105","开福区"},
                {"430111","雨花区"},{"430112","望城区"},{"430121","长沙县"},{"430181","浏阳市"},
                {"430182","宁乡市"},
            });
    }

    // ================================================================
    // 图构建
    // ================================================================

    /** 同城内区县全连接 */
    private void buildIntraCityLinks() {
        for (Map.Entry<String, List<String>> entry : cityToDistricts.entrySet()) {
            List<String> districts = entry.getValue();
            for (int i = 0; i < districts.size(); i++) {
                for (int j = i + 1; j < districts.size(); j++) {
                    link(districts.get(i), districts.get(j), 1);
                }
            }
        }
    }

    /** 跨城连接：复用 CityRoadGraph 的跨城边界 + 连接两城的首末区县 */
    private void buildBoundaryDistrictLinks() {
        // 直接从城市图获取边界城市对，连接它们各自的第一个区县
        // 简化处理：对于有数据的所有城市对，连接各自区县列表的头尾
        // 未来可扩展到真正的边界区县判断
        Set<String> linkedCityPairs = new HashSet<>();
        for (String cityCode : cityToDistricts.keySet()) {
            List<String> districts = cityToDistricts.get(cityCode);
            if (districts.isEmpty()) continue;

            // 遍历城市图中与该城市相连的邻居城市
            // 这里简化：如果有区县数据，默认同省内城市全连接
            String provinceCode = cityCode.substring(0, 2) + "0000";
            for (String otherCity : cityToDistricts.keySet()) {
                if (otherCity.equals(cityCode)) continue;
                String pairKey = cityCode.compareTo(otherCity) < 0
                        ? cityCode + ":" + otherCity : otherCity + ":" + cityCode;
                if (linkedCityPairs.contains(pairKey)) continue;

                String otherProvince = otherCity.substring(0, 2) + "0000";
                // 同省或跨省边界城市
                if (provinceCode.equals(otherProvince) || isProvinceBoundary(cityCode, otherCity)) {
                    linkedCityPairs.add(pairKey);
                    List<String> otherDistricts = cityToDistricts.get(otherCity);
                    if (otherDistricts.isEmpty()) continue;
                    // 连接两城各一个区县作为桥梁
                    link(districts.get(0), otherDistricts.get(0), 1);
                    if (districts.size() > 1 && otherDistricts.size() > 1) {
                        link(districts.get(districts.size() - 1), otherDistricts.get(otherDistricts.size() - 1), 1);
                    }
                }
            }
        }
    }

    /** 判断两个城市是否在省界上（从 CityRoadGraph 已有的跨省连接判断） */
    private boolean isProvinceBoundary(String code1, String code2) {
        String p1 = code1.substring(0, 2) + "0000";
        String p2 = code2.substring(0, 2) + "0000";
        if (p1.equals(p2)) return false;
        // 检查 cityGraph 中是否有这两个城市之间的直接连接
        // 简化：跨省且在相邻省份列表中
        return true; // 默认跨省都连（进一步优化可查 cityGraph）
    }

    // ================================================================
    // 辅助
    // ================================================================

    private void addDistricts(String cityCode, String[][] rows) {
        List<String> list = new ArrayList<>();
        for (String[] row : rows) {
            String code = row[0], name = row[1];
            String provinceCode = cityCode.substring(0, 2) + "0000";
            String provinceName = cityGraph.provinceName(provinceCode);
            districtInfo.put(code, new DistrictInfo(name, cityCode, provinceCode, provinceName));
            nameToCode.putIfAbsent(name, code);
            list.add(code);
            graph.putIfAbsent(code, new ArrayList<>());
        }
        cityToDistricts.put(cityCode, list);
    }

    private void link(String a, String b, int cost) {
        graph.computeIfAbsent(a, k -> new ArrayList<>()).add(new Edge(b, cost));
        graph.computeIfAbsent(b, k -> new ArrayList<>()).add(new Edge(a, cost));
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ================================================================
    // 内部类型
    // ================================================================

    private record Edge(String to, int cost) {}
    private record PathNode(String code, int cost) {}
    public record DistrictInfo(String name, String cityCode, String provinceCode, String provinceName) {}
}
