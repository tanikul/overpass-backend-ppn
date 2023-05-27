package com.overpass.service;

import java.util.List;

import org.springframework.security.core.Authentication;

import com.overpass.model.LightBulb;
import com.overpass.model.Overpass;
import com.overpass.model.OverpassStatus;
import com.overpass.model.ResponseDataTable;
import com.overpass.model.SearchDataTable;

public interface OverpassService {

	public void insertOverpass(Overpass overpass, Authentication authentication);
	public void updateOverpass(Overpass overpass, Authentication authentication);
	public ResponseDataTable<Overpass> searchOverpass(SearchDataTable<Overpass> data);
	public void deleteOverpass(String id);
	public List<Overpass> getOverpasses(Integer provinceId, Integer amphurId, Integer tumbon);
	public List<Overpass> searchOverpassesByUserId(Integer provinceId, Integer amphurId, Integer tumbonId, String userId, String overpassId);
	public List<LightBulb> getLightBulbAll();
	public List<OverpassStatus> getOverpassStatusByGroupId(String id);
}
