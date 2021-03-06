package org.hibernate.cache.ehcache.internal.strategy;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.ehcache.internal.regions.EhcacheTransactionalDataRegion;


/**
 * @author Alex Snaps
 */
public class ItemValueExtractor extends AbstractReadWriteEhcacheAccessStrategy {


	/**
	 * Creates a read/write cache access strategy around the given cache region.
	 */
	public ItemValueExtractor(EhcacheTransactionalDataRegion region, SessionFactoryOptions settings) {
		super(region, settings);
	}


	public static <T> T getValue(final Object entry) {
		if(!(entry instanceof Item)) {
			throw new IllegalArgumentException("Entry needs to be of type " + Item.class.getName());
		}
		return (T)((Item)entry).getValue();
	}
}
