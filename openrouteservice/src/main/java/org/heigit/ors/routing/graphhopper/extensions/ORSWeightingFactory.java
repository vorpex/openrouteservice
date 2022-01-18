/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1 
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library; 
 *  if not, see <https://www.gnu.org/licenses/>.  
 */
package org.heigit.ors.routing.graphhopper.extensions;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.util.ConditionalSpeedCalculator;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.*;
import com.graphhopper.storage.ConditionalEdges;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.Parameters;
import org.heigit.ors.routing.ProfileWeighting;
import org.heigit.ors.routing.graphhopper.extensions.flagencoders.FlagEncoderNames;
import org.heigit.ors.routing.graphhopper.extensions.util.ORSParameters;
import org.heigit.ors.routing.graphhopper.extensions.weighting.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @deprecated This class does not work with the design of GH 4.0
 * The class OrsWeightingFactoryGh4 is meant to be a cleaned up replacement
 * of this one, but does not provide all the functionality yet.
 */
@Deprecated
public class ORSWeightingFactory implements WeightingFactory {

	private final GraphHopperStorage graphStorage;
	private final FlagEncoder encoder;

	public ORSWeightingFactory(GraphHopperStorage ghStorage, FlagEncoder encoder) {
		this.graphStorage= ghStorage;
		this.encoder = encoder;
	}

	public Weighting createWeighting(PMap hintsMap, boolean disableTurnCosts) {
		return createWeighting(null, hintsMap, disableTurnCosts);
	}

	@Override
	public Weighting createWeighting(Profile profile, PMap hintsMap, boolean disableTurnCosts) {
		TraversalMode tMode = encoder.supportsTurnCosts() ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED;
		if (hintsMap.has(Parameters.Routing.EDGE_BASED))
			tMode = hintsMap.getBool(Parameters.Routing.EDGE_BASED, false) ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED;
		if (tMode.isEdgeBased() && !encoder.supportsTurnCosts()) {
			throw new IllegalArgumentException("You need a turn cost extension to make use of edge_based=true, e.g. use car|turn_costs=true");
		}

		String strWeighting = hintsMap.getString("weighting_method", "").toLowerCase();
		if (Helper.isEmpty(strWeighting))
			strWeighting = hintsMap.getString("weighting", "");

		Weighting result = null;

		if("true".equalsIgnoreCase(hintsMap.getString("isochroneWeighting", "false")))
			return createIsochroneWeighting(hintsMap, encoder);

		if ("shortest".equalsIgnoreCase(strWeighting))
		{
			result = new ShortestWeighting(encoder);
		}
		else if ("fastest".equalsIgnoreCase(strWeighting))
		{
			TurnCostProvider tcp = null; // TODO: setup correctly
			if (encoder.supports(PriorityWeighting.class) && !encoder.toString().equals(FlagEncoderNames.HEAVYVEHICLE))
				result = new PriorityWeighting(encoder, hintsMap, tcp);
			else
				result = new FastestWeighting(encoder, hintsMap);
		}
		else  if ("priority".equalsIgnoreCase(strWeighting))
		{
			result = new PreferencePriorityWeighting(encoder, hintsMap);
		}
		else
		{
			if (encoder.supports(PriorityWeighting.class))
			{
				if ("recommended_pref".equalsIgnoreCase(strWeighting))
				{
					result = new PreferencePriorityWeighting(encoder, hintsMap);
				}
				else if ("recommended".equalsIgnoreCase(strWeighting))
					result = new OptimizedPriorityWeighting(encoder, hintsMap);
				else
					result = new FastestSafeWeighting(encoder, hintsMap);
			}
			else
				result = new FastestWeighting(encoder, hintsMap);
		}

		if (hasTimeDependentSpeed(hintsMap) && hasConditionalSpeed(encoder, graphStorage)) {
			result.setSpeedCalculator(new ConditionalSpeedCalculator(result.getSpeedCalculator(), graphStorage, encoder));
		}

		//FIXME: turn cost weighting should probably be enabled only at query time as in GH
		/*
		if (encoder.supports(TurnWeighting.class) && !isFootBasedFlagEncoder(encoder) && graphStorage != null && !tMode.equals(TraversalMode.NODE_BASED)) {
			Path path = Paths.get(graphStorage.getDirectory().getLocation(), "turn_costs");
			File file = path.toFile();
			if (file.exists()) {
				TurnCostExtension turnCostExt = null;
				synchronized (turnCostExtensionMap) {
					turnCostExt = turnCostExtensionMap.get(graphStorage);
					if (turnCostExt == null) {
						turnCostExt = new TurnCostExtension();
						turnCostExt.init(graphStorage, graphStorage.getDirectory());
						turnCostExtensionMap.put(graphStorage, turnCostExt);
					}
				}

				result = new TurnWeighting(result, turnCostExt);
			}
		}
		*/
		// Apply soft weightings
		if (hintsMap.getBool("custom_weightings", false))
		{
			Map<String, Object> map = hintsMap.toMap();

			List<String> weightingNames = new ArrayList<>();
			for (Map.Entry<String, Object> kv : map.entrySet())
			{
				String name = ProfileWeighting.decodeName(kv.getKey());
				if (name != null && !weightingNames.contains(name))
					weightingNames.add(name);
			}

			List<Weighting> softWeightings = new ArrayList<>();

			for (String weightingName : weightingNames) {
				switch (weightingName) {
					case "steepness_difficulty":
						softWeightings.add(new SteepnessDifficultyWeighting(encoder, getWeightingProps(weightingName, map), graphStorage));
						break;
					case "avoid_hills":
						softWeightings.add(new AvoidHillsWeighting(encoder, getWeightingProps(weightingName, map), graphStorage));
						break;
					case "green":
						softWeightings.add(new GreenWeighting(encoder, getWeightingProps(weightingName, map), graphStorage));
						break;
					case "quiet":
						softWeightings.add(new QuietWeighting(encoder, getWeightingProps(weightingName, map), graphStorage));
						break;
					case "acceleration":
						softWeightings.add(new AccelerationWeighting(encoder, getWeightingProps(weightingName, map), graphStorage));
						break;
					default:
						break;
				}
			}

			if (!softWeightings.isEmpty()) {
				Weighting[] arrWeightings = new Weighting[softWeightings.size()];
				arrWeightings = softWeightings.toArray(arrWeightings);
				result = new AdditionWeighting(arrWeightings, result);
			}
		}
		return result;
	}


	private boolean hasTimeDependentSpeed(PMap hintsMap) {
		return hintsMap.getBool(ORSParameters.Weighting.TIME_DEPENDENT_SPEED, false);
	}

	private boolean hasConditionalSpeed(FlagEncoder encoder, GraphHopperStorage graphStorage) {
		return graphStorage.getEncodingManager().hasEncodedValue(EncodingManager.getKey(encoder, ConditionalEdges.SPEED));
	}

	public Weighting createIsochroneWeighting(PMap hintsMap, FlagEncoder encoder) {
        String strWeighting = hintsMap.getString("weighting_method", "").toLowerCase();
        if (Helper.isEmpty(strWeighting))
            strWeighting = hintsMap.getString("weighting", "");

        Weighting result = null;

        //Isochrones only support fastest or shortest as no path is found.
        //CalcWeight must be directly comparable to the isochrone limit

        if ("shortest".equalsIgnoreCase(strWeighting))
        {
            result = new ShortestWeighting(encoder);
        }
        else if ("fastest".equalsIgnoreCase(strWeighting)
                || "priority".equalsIgnoreCase(strWeighting)
                || "recommended_pref".equalsIgnoreCase(strWeighting)
                || "recommended".equalsIgnoreCase(strWeighting))
        {
            result = new FastestWeighting(encoder, hintsMap);
        }

        return result;
    }

	private PMap getWeightingProps(String weightingName, Map<String, Object> map)
	{
		PMap res = new PMap();

		String prefix = "weighting_#" + weightingName;
		int n = prefix.length();
		
		for (Map.Entry<String, Object> kv : map.entrySet())
		{
			String name = kv.getKey();
		    int p = name.indexOf(prefix);
		    if (p >= 0)
		    	res.putObject(name.substring(p + n + 1), kv.getValue());
		}
		
		return res;
	}
}
