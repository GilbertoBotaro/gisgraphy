/*******************************************************************************
 *   Gisgraphy Project 
 * 
 *   This library is free software; you can redistribute it and/or
 *   modify it under the terms of the GNU Lesser General Public
 *   License as published by the Free Software Foundation; either
 *   version 2.1 of the License, or (at your option) any later version.
 * 
 *   This library is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *   Lesser General Public License for more details.
 * 
 *   You should have received a copy of the GNU Lesser General Public
 *   License along with this library; if not, write to the Free Software
 *   Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA
 * 
 *  Copyright 2008  Gisgraphy project 
 *  David Masclet <davidmasclet@gisgraphy.com>
 *  
 *  
 *******************************************************************************/
package com.gisgraphy.importer;

import static com.gisgraphy.domain.geoloc.entity.GisFeature.NAME_MAX_LENGTH;
import static com.gisgraphy.fulltext.FulltextQuerySolrHelper.MIN_SCORE;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.FlushMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import com.gisgraphy.domain.geoloc.entity.Adm;
import com.gisgraphy.domain.geoloc.entity.City;
import com.gisgraphy.domain.geoloc.entity.CitySubdivision;
import com.gisgraphy.domain.geoloc.entity.GisFeature;
import com.gisgraphy.domain.geoloc.entity.ZipCode;
import com.gisgraphy.domain.repository.IAdmDao;
import com.gisgraphy.domain.repository.ICityDao;
import com.gisgraphy.domain.repository.ICitySubdivisionDao;
import com.gisgraphy.domain.repository.IGisFeatureDao;
import com.gisgraphy.domain.repository.IIdGenerator;
import com.gisgraphy.domain.repository.ISolRSynchroniser;
import com.gisgraphy.domain.repository.IZipCodeDao;
import com.gisgraphy.domain.valueobject.GISSource;
import com.gisgraphy.domain.valueobject.NameValueDTO;
import com.gisgraphy.fulltext.FullTextSearchEngine;
import com.gisgraphy.fulltext.FulltextQuery;
import com.gisgraphy.fulltext.FulltextQuerySolrHelper;
import com.gisgraphy.fulltext.FulltextResultsDto;
import com.gisgraphy.fulltext.IFullTextSearchEngine;
import com.gisgraphy.fulltext.SolrResponseDto;
import com.gisgraphy.helper.GeolocHelper;
import com.gisgraphy.helper.StringHelper;
import com.gisgraphy.service.ServiceException;
import com.vividsolutions.jts.geom.Point;

/**
 * Import the zipcode from a Geonames dump file.
 * 
 * @author <a href="mailto:david.masclet@gisgraphy.com">David Masclet</a>
 */
public class GeonamesZipCodeSimpleImporter extends AbstractSimpleImporterProcessor {
	
	protected static final Logger logger = LoggerFactory.getLogger(GeonamesZipCodeSimpleImporter.class);

    protected IGisFeatureDao gisFeatureDao;

    protected IAdmDao admDao;

    protected IFullTextSearchEngine fullTextSearchEngine;

    protected ISolRSynchroniser solRSynchroniser;

    protected ICityDao cityDao;
    
    protected ICitySubdivisionDao citySubdivisionDao;

    protected IZipCodeDao zipCodeDao;
    
    protected IIdGenerator IdGenerator;
    
    LabelGenerator labelGenerator = LabelGenerator.getInstance();




    protected int[] accuracyToDistance = { 50000, 50000, 40000, 10000, 10000, 5000, 3000 };
    

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#processData
     * (java.lang.String)
     */
    @Override
    protected void processData(String line) {
	String[] fields = line.split("\t");

	/*
	 * line table has the following fields :
	 * --------------------------------------------------- 0 country code :
	 * 1 postal code 2 place name 3 admin1 name 4 admin1 code 5 admin2 name
	 * 6 admin2 code2 7 admin3 name 8 admin3 code 9 latitude 10 longitude 11
	 * accuracy accuracy
	 * 
	 * Accuracy is an integer, the higher the better : 1 : estimated as
	 * average from numerically neigbouring postal codes 3 : same postal
	 * code, other name 4 : place name from geonames db 6 : postal code area
	 * centroid
	 */

	// check that the csv file line is in a correct format
	//checkNumberOfColumn(fields);

	String code = null;
	int accuracy = 0;
	Point zipPoint = null;
	String countryCode=null;

	//check required field
	if (!isEmptyField(fields, 0, true)) {
		countryCode= fields[0];
	}

	if (!isEmptyField(fields, 1, true)) {
	    code = fields[1];
	    if (ImporterHelper.isUnwantedZipCode(code)){
	    	return;
	    }
	}

	//check required field
	if (!isEmptyField(fields, 2, true)) {
		//nothing to do just check
	}

	if (!isEmptyField(fields, 11, false)) {
	    accuracy = new Integer(fields[11]);
	}

	// Location
	if (!isEmptyField(fields, 10, true) && !isEmptyField(fields, 9, true)) {
	    zipPoint = GeolocHelper.createPoint(new Float(fields[10]), new Float(fields[9]));
	}
	boolean found = getByShape(countryCode, code, zipPoint);
	if (found){
		//we find a city, we return
		return;
	}
	
	Long featureId = findFeature(fields, zipPoint, getAccurateDistance(accuracy));
	
	GisFeature gisFeature;
	if (featureId != null) {
	    logger.info(dumpFields(fields) +" returns "+ featureId );
	    gisFeature = addAndSaveZipCodeToFeature(code, featureId);
	    logger.info("Adding zip " + fields[1] +" to "+gisFeature);
	} else {
	    logger.warn(dumpFields(fields) +" returns nothings ");
	    gisFeature = addNewEntityAndZip(fields);
	    logger.info("Adding new zip " + fields[1] +" to "+gisFeature);
	}
    }

	protected boolean getByShape(String countryCode, String code, Point zipPoint) {
		boolean found = false;
		GisFeature cityByShape = cityDao.getByShape(zipPoint,countryCode,true);
		if (cityByShape!=null){
			ZipCode zipCode = new ZipCode(code,countryCode);
			//if (feature.getZipCodes() == null || !feature.getZipCodes().contains(zipCode)) {
			cityByShape.addZipCode(zipCode);
			cityDao.save((City)cityByShape);
			found=true;
		} else {
			 cityByShape = cityDao.getByShape(zipPoint,countryCode,false);
			 if (cityByShape!=null){
					ZipCode zipCode = new ZipCode(code,countryCode);
					//if (feature.getZipCodes() == null || !feature.getZipCodes().contains(zipCode)) {
					cityByShape.addZipCode(zipCode);
					cityDao.save((City)cityByShape);
					found=true;
				} 
		}
		
		//try with subdivision too (in addition)
		CitySubdivision citySubdivision = citySubdivisionDao.getByShape(zipPoint, countryCode);
		if (citySubdivision!=null){
			ZipCode zipCode = new ZipCode(code,countryCode);
			citySubdivision.addZipCode(zipCode);
			citySubdivisionDao.save(citySubdivision);
			found = true;
		} 
		return found;
	}

    protected Long findFeature(String[] fields,  Point zipPoint,int maxDistance) {
    
	String query;
	boolean extendedsearch;
	/*if (fields[3] != null) {//adm1Name
	    query = fields[2] + " " + fields[3];
	    extendedsearch = true;
	} else {*/
	    query = fields[2];//name
	    extendedsearch = false;
	//}
	FulltextResultsDto results = doAFulltextSearch(query,fields[0],zipPoint);
	if (results.getNumFound() == 0) {
	   /* if (extendedsearch) {
		// do a basic search
		results = doAFulltextSearch(fields[2], fields[0],zipPoint);
		if (results.getResultsSize() == 0) {
		    // oops, no results
			logger.error("find feature for "+query+" around "+zipPoint+" returns nothing");
		    return null;
		} else if (results.getNumFound() == 1) {
		    // we found the one!
			logger.error("find feature for "+query+" around "+zipPoint+" returns (1) "+results.getResults().get(0).getName());
			if (StringHelper.isSameName(fields[2], results.getResults().get(0).getName())){
				return results.getResults().get(0).getFeature_id();
			} else {
				return null;
			}
		} else {
		    // more than one match iterate and calculate distance and
		    // take the best one by score
			logger.error("find feature for "+query+" around "+zipPoint+" returns (+)"+results.getResults().get(0).getName());
			if (StringHelper.isSameName(fields[2], results.getResults().get(0).getName())){
				return results.getResults().get(0).getFeature_id();
			} else {
				return null;
			}
			//return findNearest(zipPoint, maxDistance, results);
		}
	    } else {*/
		// no features matches in basic search!
		return null;

	   // }
	} else if (results.getResults().size() == 1) {
	    // we found the one!
		if (StringHelper.isSameName(fields[2], results.getResults().get(0).getName())  ||  results.getResults().get(0).getScore()> MIN_SCORE || StringHelper.isSameAlternateNames(fields[2], results.getResults().get(0).getName_alternates())){
			return results.getResults().get(0).getFeature_id();
		} else {
			return null;
		}
	} else {
		// more than one match iterate and calculate distance and
	    // take the best one by score
		for (SolrResponseDto result : results.getResults()){
			//score is important for case when we search Munchen and city name is Munich
			if (StringHelper.isSameName(fields[2], result.getName()) ||  result.getScore()> MIN_SCORE || StringHelper.isSameAlternateNames(fields[2], result.getName_alternates())){
				return result.getFeature_id();
			} 
			//shortcut : if score is less than this, the next one will be automatically less, 
			if (result.getScore()<15){
				return null;
			}
		}
		//else {
			return null;
		//}
	   // return findNearest(zipPoint, maxDistance, results);
	}

    }

    protected Long findNearest(Point zipPoint, int maxDistance, FulltextResultsDto results) {
    	Long nearestFeatureId = null;
    	double nearestDistance = 0;
    	for (SolrResponseDto dto : results.getResults()) {
    		Point dtoPoint = GeolocHelper.createPoint(new Float(dto.getLng()), new Float(dto.getLat()));
    		if (nearestFeatureId == null) {
    			nearestFeatureId = dto.getFeature_id();
    			nearestDistance = GeolocHelper.distance(zipPoint, dtoPoint);
    		} else {
    			double distance = GeolocHelper.distance(zipPoint, dtoPoint);
    			if (distance > maxDistance) {
    				logger.info(dto.getFeature_id() + " is too far and is not candidate");
    			} else {
    				if (distance < nearestDistance) {
    					logger.info(dto.getFeature_id() + "is nearest than " + nearestFeatureId);
    					nearestFeatureId = dto.getFeature_id();
    					nearestDistance = distance;
    				}
    			}
    		}
	}
	return nearestFeatureId;
    }

    protected int getAccurateDistance(int accuracyLevel) {
	if (accuracyLevel>accuracyToDistance.length-1){
	    accuracyLevel =  accuracyToDistance.length - 1;
	} else if (accuracyLevel<0){
	    accuracyLevel = 0;
	}
	return accuracyToDistance[accuracyLevel];
    }

    protected GisFeature addNewEntityAndZip(String[] fields) {
	City city = new City();
	Point location = null;
	long nextFeatureId = IdGenerator.getNextFeatureId();
	city.setFeatureId(nextFeatureId);
	String name = fields[2];
	if (name.length() > NAME_MAX_LENGTH){
		logger.warn(name + "is too long");
		name= name.substring(0, NAME_MAX_LENGTH-1);
	}
	city.setName(name);
	// Location
	if (!isEmptyField(fields, 9, true) && !isEmptyField(fields, 10, true)) {
	    location = GeolocHelper.createPoint(new Float(fields[10]), new Float(fields[9]));
		city.setLocation(location);
		city.setAdminCentreLocation(location);
	}
	city.setFeatureClass("P");
	city.setFeatureCode("PPL");
	city.setSource(GISSource.GEONAMES_ZIP);
	String countryCode=null;
	if (!isEmptyField(fields, 0, false)){
		countryCode = fields[0];
	}
	city.setCountryCode(countryCode);
	setAdmCodesWithCSVOnes(fields, city);
	Adm adm = null;
	/*if (importerConfig.isTryToDetectAdmIfNotFound()) {
	    adm = this.admDao.suggestMostAccurateAdm(fields[0], fields[4], fields[6], fields[8], null, city);
	    logger.info("suggestAdm=" + adm);
	} else {
	    adm = this.admDao.getAdm(fields[0], fields[4], fields[6], fields[8], null);
	}*/
	List<Adm > adms = admDao.ListByShape(location, countryCode);
	if (adms.size()>0){
		adm = adms.get(adms.size()-1);
	}
	/*setAdmCodesWithLinkedAdmOnes(adm, gisFeature, importerConfig
		.isSyncAdmCodesWithLinkedAdmOnes());*/
	setAdmNames(adms, city);

	city.setAdm(adm);
	setAdmCodesWithLinkedAdmOnes(adm, city, importerConfig.isSyncAdmCodesWithLinkedAdmOnes());
	setAdmNames(adm, city);
	city.addZipCode(new ZipCode(fields[1],countryCode));
	
	city.setAlternateLabels(labelGenerator.generateLabels(city));
	city.setLabel(labelGenerator.generateLabel(city));
	city.setFullyQualifiedName(labelGenerator.getFullyQualifiedName(city));
	
	cityDao.save(city);
	//we do not return the saved entity for test purpose
	return city;
    }

    protected GisFeature addAndSaveZipCodeToFeature(String code, Long featureId) {
	GisFeature feature = gisFeatureDao.getByFeatureId(featureId);
	if (feature == null) {
	    return null;
	}
	ZipCode zipCode = new ZipCode(code,feature.getCountryCode());
	//if (feature.getZipCodes() == null || !feature.getZipCodes().contains(zipCode)) {
	    feature.addZipCode(zipCode);
	    return gisFeatureDao.save(feature);
	//} else {
	  //  logger.warn("the zipcode " + code + " already exists for feature " + featureId);
	    //return feature;
	//}
    }
    
    private void setAdmNames(List<Adm> adms, GisFeature gisFeature) {
    	if (adms == null) {
    	    return;
    	}
    	int level =1;
    	for (Adm adm:adms){
    		if(adm!=null && level <=5){
    			gisFeature.setAdmName(level, adm.getName());
    			level=level+1;
    		}
        }
    }

    protected FulltextResultsDto doAFulltextSearch(String query, String countryCode,Point location) {
	FulltextQuery fulltextQuery;
	try {
		fulltextQuery = new FulltextQuery(query);
	} catch (IllegalArgumentException e) {
		logger.error("can not create a fulltext query for "+query);
		return new FulltextResultsDto();
	}
	fulltextQuery.limitToCountryCode(countryCode).around(location);
	fulltextQuery.withPlaceTypes(com.gisgraphy.fulltext.Constants.CITY_AND_CITYSUBDIVISION_PLACETYPE);

	FulltextResultsDto results;
	try {
		results = fullTextSearchEngine.executeQuery(fulltextQuery);
	} catch (ServiceException e) {
		logger.error("error when executing a fulltext search "+e.getMessage(),e);
		return new FulltextResultsDto();
	}
	return results;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#shouldBeSkiped
     * ()
     */
    @Override
    public boolean shouldBeSkipped() {
	return !importerConfig.isGeonamesImporterEnabled();
    }


    private void setAdmNames(Adm adm, GisFeature gisFeature) {
	if (adm == null) {
	    return;
	}
	Adm admTemp = adm;
	do {
	    if (admTemp.getLevel() == 1) {
		gisFeature.setAdm1Name(admTemp.getName());
	    } else if (admTemp.getLevel() == 2) {
		gisFeature.setAdm2Name(admTemp.getName());
	    } else if (admTemp.getLevel() == 3) {
		gisFeature.setAdm3Name(admTemp.getName());
	    } else if (admTemp.getLevel() == 4) {
		gisFeature.setAdm4Name(admTemp.getName());
	    }
	    admTemp = admTemp.getParent();
	} while (admTemp != null);

    }

    private void setAdmCodesWithLinkedAdmOnes(Adm adm, GisFeature gisFeature, boolean syncAdmCodesWithLinkedAdmOnes) {

	if (syncAdmCodesWithLinkedAdmOnes) {
	    // reset adm code because we might link to an adm3 and adm4 code
	    // have
	    // been set
	    setAdmCodesToNull(gisFeature);
	    if (adm != null) {
		if (adm.getAdm1Code() != null) {
		    gisFeature.setAdm1Code(adm.getAdm1Code());
		}
		if (adm.getAdm2Code() != null) {
		    gisFeature.setAdm2Code(adm.getAdm2Code());
		}
		if (adm.getAdm3Code() != null) {
		    gisFeature.setAdm3Code(adm.getAdm3Code());
		}
		if (adm.getAdm4Code() != null) {
		    gisFeature.setAdm4Code(adm.getAdm4Code());
		}
	    }

	}
    }

    private void setAdmCodesToNull(GisFeature gisFeature) {
	gisFeature.setAdm1Code(null);
	gisFeature.setAdm2Code(null);
	gisFeature.setAdm3Code(null);
	gisFeature.setAdm4Code(null);
    }

    private void setAdmCodesWithCSVOnes(String[] fields, GisFeature gisFeature) {
	logger.debug("in setAdmCodesWithCSVOnes");
	if (!isEmptyField(fields, 4, false)) {
	    gisFeature.setAdm1Code(fields[4]);
	}
	if (!isEmptyField(fields, 6, false)) {
	    gisFeature.setAdm2Code(fields[6]);
	}
	if (!isEmptyField(fields, 8, false)) {
	    gisFeature.setAdm3Code(fields[8]);
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#
     * shouldIgnoreFirstLine()
     */
    @Override
    protected boolean shouldIgnoreFirstLine() {
	return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#
     * shouldIgnoreComments()
     */
    @Override
    protected boolean shouldIgnoreComments() {
	return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#
     * setCommitFlushMode()
     */
    @Override
    protected void setCommitFlushMode() {
	this.gisFeatureDao.setFlushMode(FlushMode.COMMIT);
	this.cityDao.setFlushMode(FlushMode.COMMIT);
	this.admDao.setFlushMode(FlushMode.COMMIT);
	this.zipCodeDao.setFlushMode(FlushMode.COMMIT);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#flushAndClear
     * ()
     */
    @Override
    protected void flushAndClear() {
	this.gisFeatureDao.flushAndClear();
	this.cityDao.flushAndClear();
	this.admDao.flushAndClear();
	this.zipCodeDao.flushAndClear();
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#
     * getNumberOfColumns()
     */
    @Override
    protected int getNumberOfColumns() {
	return 12;
    }

    /**
     * @param cityDao
     *            The CityDao to set
     */
    @Required
    public void setCityDao(ICityDao cityDao) {
	this.cityDao = cityDao;
    }
    
    

    /**
	 * @param citySubdivisionDao the citySubdivisionDao to set
	 */
    @Required
	public void setCitySubdivisionDao(ICitySubdivisionDao citySubdivisionDao) {
		this.citySubdivisionDao = citySubdivisionDao;
	}

	/**
     * @param gisFeatureDao
     *            The GisFeatureDao to set
     */
    @Required
    public void setGisFeatureDao(IGisFeatureDao gisFeatureDao) {
	this.gisFeatureDao = gisFeatureDao;
    }

    /**
     * @param admDao
     *            the admDao to set
     */
    @Required
    public void setAdmDao(IAdmDao admDao) {
	this.admDao = admDao;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#setup()
     */
    @Override
    protected void setup() {
    	super.setup();
    	FullTextSearchEngine.disableLogging=true;
    	IdGenerator.sync();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#tearDown()
     */
	@Override
	protected void tearDown() {
		String savedMessage = this.statusMessage;
		FullTextSearchEngine.disableLogging=false;
		this.statusMessage = internationalisationService
				.getString("import.teardown");
		try {
			super.tearDown();
			if (!solRSynchroniser.commit()) {
				logger.warn("The commit in tearDown of "
						+ this.getClass().getSimpleName()
						+ " has failed, the uncommitted changes will be commited with the auto commit of solr in few minuts");
			}
			//solRSynchroniser.optimize();
		} finally {
			this.statusMessage = savedMessage;
		}
	}

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#getFiles()
     */
    @Override
    protected File[] getFiles() {
    	return ImporterHelper.listCountryFilesToImport(importerConfig.getGeonamesZipCodeDir());
    }
    
    @Override
    Integer getImportKey() {
    	return 0;
    }

    /**
     * @param solRSynchroniser
     *            the solRSynchroniser to set
     */
    @Required
    public void setSolRSynchroniser(ISolRSynchroniser solRSynchroniser) {
	this.solRSynchroniser = solRSynchroniser;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.gisgraphy.domain.geoloc.importer.IGeonamesProcessor#rollback()
     */
    public List<NameValueDTO<Integer>> rollback() {
	List<NameValueDTO<Integer>> deletedObjectInfo = new ArrayList<NameValueDTO<Integer>>();
	// we first reset subClass
	int deletedgis = zipCodeDao.deleteAll();
	logger.warn("deleting zipCodes...");
	// we don't want to remove adm because some feature can be linked again
	if (deletedgis != 0) {
	    deletedObjectInfo.add(new NameValueDTO<Integer>(GisFeature.class.getSimpleName(), deletedgis));
	}
	resetStatus();
	return deletedObjectInfo;
    }

    @Required
    public void setZipCodeDao(IZipCodeDao zipCodeDao) {
	this.zipCodeDao = zipCodeDao;
    }

    @Required
    public void setFullTextSearchEngine(IFullTextSearchEngine fullTextSearchEngine) {
        this.fullTextSearchEngine = fullTextSearchEngine;
    }

    @Required
	public void setIdGenerator(IIdGenerator idGenerator) {
		IdGenerator = idGenerator;
	}

}
