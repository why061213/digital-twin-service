package com.jushen.digitaltwin.config;

import com.jushen.digitaltwin.model.City;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "simulation")
public class SimulationProperties {

    private CityConfig headquarters;
    private List<CityConfig> cities = new ArrayList<>();
    private List<String> cargoes = new ArrayList<>();
    private List<String> plates = new ArrayList<>();

    // getters & setters
    public CityConfig getHeadquarters() { return headquarters; }
    public void setHeadquarters(CityConfig headquarters) { this.headquarters = headquarters; }
    public List<CityConfig> getCities() { return cities; }
    public void setCities(List<CityConfig> cities) { this.cities = cities; }
    public List<String> getCargoes() { return cargoes; }
    public void setCargoes(List<String> cargoes) { this.cargoes = cargoes; }
    public List<String> getPlates() { return plates; }
    public void setPlates(List<String> plates) { this.plates = plates; }

    public static class CityConfig {
        private String name;
        private double lng;
        private double lat;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getLng() { return lng; }
        public void setLng(double lng) { this.lng = lng; }
        public double getLat() { return lat; }
        public void setLat(double lat) { this.lat = lat; }

        public City toCity() {
            return new City(name, lng, lat);
        }
    }
}