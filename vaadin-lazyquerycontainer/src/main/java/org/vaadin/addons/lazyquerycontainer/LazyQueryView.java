/**
 * Copyright 2010 Tommi S.E. Laukkanen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaadin.addons.lazyquerycontainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.vaadin.data.Item;
import com.vaadin.data.Property;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.data.Property.ValueChangeNotifier;

/**
 * Lazy loading implementation of QueryView. This implementation supports
 * lazy loading, batch loading, caching and sorting. LazyQueryView supports
 * debug properties which will be filled with debug information when they
 * exist in query definition. The debug property IDs are defined as string 
 * constants with the following naming convention: DEBUG_PROPERTY_XXXX.
 * 
 * LazyQueryView implements mainly batch loading, caching and debug
 * functionalities. When data is sorted old query is discarded and new 
 * constructed with QueryFactory and new sort state.
 * 
 * @author Tommi S.E. Laukkanen 
 */
public class LazyQueryView implements QueryView, ValueChangeListener {
	private static final long serialVersionUID = 1L;

	public static final String DEBUG_PROPERTY_ID_QUERY_INDEX="DEBUG_PROPERTY_ID_QUERY_COUT";
	public static final String DEBUG_PROPERTY_ID_BATCH_INDEX="DEBUG_PROPERTY_ID_BATCH_INDEX";
	public static final String DEBUG_PROPERTY_ID_BATCH_QUERY_TIME="DEBUG_PROPERTY_ID_ACCESS_COUNT";
	
	public static final String PROPERTY_ID_ITEM_STATUS="PROPERTY_ID_ITEM_STATUS";
	
	private int maxCacheSize=1000;
	private int queryCount=0;
	
	private QueryDefinition definition;
	private QueryFactory factory;
	private Query query;

	private Object[] sortPropertyIds; 
	private boolean[] ascendingStates;
	
	private LinkedList<Integer> itemCacheAccessLog=new LinkedList<Integer>();
	private Map<Integer,Item> itemCache=new HashMap<Integer,Item>();
	private Map<Property,Item> propertyItemMapCache=new HashMap<Property,Item>();
	
	private List<Item> addedItems=new ArrayList<Item>();
	private List<Item> modifiedItems=new ArrayList<Item>();
	private List<Item> removedItems=new ArrayList<Item>();
	
	/**
	 * Constructs LazyQueryView with DefaultQueryDefinition and the given QueryFactory.
	 * @param factory The QueryFactory to be used.
	 * @param batchSize The batch size to be used when loading data.
	 */
	public LazyQueryView(QueryFactory factory, int batchSize) {
		initialize(new DefaultQueryDefinition(batchSize),factory);
	}
	
	/**
	 * Constructs LazyQueryView with given QueryDefinition and QueryFactory. The role
	 * of this constructor is to enable use of custom QueryDefinition implementations.
	 * @param definition
	 * @param factory
	 */
	public LazyQueryView(QueryDefinition definition, QueryFactory factory) {
		initialize(definition,factory);
	}
	
	private void initialize(QueryDefinition definition, QueryFactory factory) {
		this.definition=definition;
		this.factory=factory;
		this.factory.setQueryDefinition(definition);
		this.sortPropertyIds=new Object[0];
		this.ascendingStates=new boolean[0];		
	}
	

	public QueryDefinition getDefinition() {
		return definition;
	}


	public void sort(Object[] sortPropertyIds, boolean[] ascendingStates) {
		this.sortPropertyIds=sortPropertyIds;
		this.ascendingStates=ascendingStates;
		refresh();
	}
	

	public void refresh() {
		
		for(Property property : propertyItemMapCache.keySet()) {
			if(property instanceof ValueChangeNotifier) {
				ValueChangeNotifier notifier=(ValueChangeNotifier) property;
				notifier.removeListener(this);
			}
		}
		
		query=null;
		itemCache.clear();
		itemCacheAccessLog.clear();		
		propertyItemMapCache.clear();
		
		discard();
	}


	public int size() {
		return getQuery().size()+addedItems.size();
	}

	public int getBatchSize() {
		return definition.getBatchSize();
	}


	public Item getItem(int index) {
		int addedItemCount = addedItems.size();
		if (index < addedItemCount) {
			//an item from the addedItems was requested
			return addedItems.get(index);
		}
		if(!itemCache.containsKey(index - addedItemCount)) {
			//item is not in our cache, ask the query for more items
			queryItem(index - addedItemCount);
		} else {
			//item is already in our cache
			//refresh cache access log.		
			itemCacheAccessLog.remove(new Integer(index));
			itemCacheAccessLog.addLast(new Integer(index));
		}
		
		return itemCache.get(index - addedItemCount);
	}
	
	private void queryItem(int index) {
		int batchSize=getBatchSize();
		int startIndex=index-index%batchSize;
		int count=Math.min(batchSize, getQuery().size()-startIndex);
		
		long queryStartTime=System.currentTimeMillis();
		//load more items
		List<Item> items=getQuery().loadItems(startIndex, count);
		long queryEndTime=System.currentTimeMillis();
		
		for(int i=0;i<count;i++) {
			int itemIndex=startIndex+i;
			Item item=items.get(i);
			
			itemCache.put(itemIndex,item);
			itemCacheAccessLog.addLast(itemIndex);
		}
		
		for(int i=0;i<count;i++) {
			Item item=items.get(i);
			
			if(item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_INDEX)!=null) {
				item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_INDEX).setReadOnly(false);
				item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_INDEX).setValue(startIndex/batchSize);
				item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_INDEX).setReadOnly(true);
			}
			if(item.getItemProperty(DEBUG_PROPERTY_ID_QUERY_INDEX)!=null) {
				item.getItemProperty(DEBUG_PROPERTY_ID_QUERY_INDEX).setReadOnly(false);
				item.getItemProperty(DEBUG_PROPERTY_ID_QUERY_INDEX).setValue(queryCount);
				item.getItemProperty(DEBUG_PROPERTY_ID_QUERY_INDEX).setReadOnly(true);
			}
			if(item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_QUERY_TIME)!=null) {
				item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_QUERY_TIME).setReadOnly(false);
				item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_QUERY_TIME).setValue(queryEndTime-queryStartTime);
				item.getItemProperty(DEBUG_PROPERTY_ID_BATCH_QUERY_TIME).setReadOnly(true);
			}
			
			for(Object propertyId : item.getItemPropertyIds()) {
				Property property=item.getItemProperty(propertyId);
				if(property instanceof ValueChangeNotifier) {
					ValueChangeNotifier notifier=(ValueChangeNotifier) property;
					notifier.addListener(this);
					propertyItemMapCache.put(property, item);		
				}
			}

		}
		
		// Evict items from cache if cache size exceeds max cache size
		int counter=0;
		while(itemCache.size()>maxCacheSize) {
			int firstIndex=itemCacheAccessLog.getFirst();
			Item firstItem=itemCache.get(firstIndex);

			// Remove oldest item in cache access log if it is not modified or removed.
			if(!modifiedItems.contains(firstItem)&&!removedItems.contains(firstItem)) {			
				itemCacheAccessLog.remove(new Integer(firstIndex));
				itemCache.remove(firstIndex);
	
				for(Object propertyId : firstItem.getItemPropertyIds()) {
					Property property=firstItem.getItemProperty(propertyId);
					if(property instanceof ValueChangeNotifier) {
						ValueChangeNotifier notifier=(ValueChangeNotifier) property;
						notifier.removeListener(this);
						propertyItemMapCache.remove(property);
					}
				}
			
			} else {
				itemCacheAccessLog.remove(firstIndex);
				itemCacheAccessLog.addLast(firstIndex);
			}
			
			// Break from loop if entire cache has been iterated (all items are modified).
			counter++;
			if(counter>itemCache.size()) {
				break;
			}
		}
	}

	private Query getQuery() {
		if(query==null) {
			query=factory.constructQuery(sortPropertyIds,ascendingStates);
			queryCount++;
		}
		return query;
	}


	public int addItem() {
		Item item=getQuery().constructItem();
		if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.Added);
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
		}
		addedItems.add(0,item);
		return 0;
	}


	public void valueChange(ValueChangeEvent event) {
		Property property=event.getProperty();
		Item item=propertyItemMapCache.get(property);
		if(property==item.getItemProperty(PROPERTY_ID_ITEM_STATUS)) {
			return;
		}
		if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null&&
				((QueryItemStatus)item.getItemProperty(PROPERTY_ID_ITEM_STATUS).getValue())!=QueryItemStatus.Modified) {
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.Modified);
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
		}
		modifiedItems.add(item);
	}
	

	public void removeItem(int index) {
		Item item=getItem(index);
		
		if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.Removed);
			item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
		}
		
		for(Object propertyId : item.getItemPropertyIds()) {
			Property property=item.getItemProperty(propertyId);
			property.setReadOnly(true);
		}
				
		removedItems.add(item);
	}


	public void removeAllItems() {
		getQuery().deleteAllItems();
	}


	public boolean isModified() {
		return addedItems.size()!=0||modifiedItems.size()!=0||removedItems.size()!=0;
	}


	public void commit() {
		for(Item item : addedItems) {
			if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.None);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
			}			
		}
		for(Item item : modifiedItems) {
			if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.None);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
			}			
		}
		for(Item item : removedItems) {
			if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.None);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
			}			
		}
		getQuery().saveItems(addedItems, modifiedItems, removedItems);
		addedItems.clear();
		modifiedItems.clear();
		removedItems.clear();
	}

	public void discard() {
		for(Item item : addedItems) {
			if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.None);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
			}			
		}
		for(Item item : modifiedItems) {
			if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.None);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
			}			
		}
		for(Item item : removedItems) {
			if(item.getItemProperty(PROPERTY_ID_ITEM_STATUS)!=null) {
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(false);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setValue(QueryItemStatus.None);
				item.getItemProperty(PROPERTY_ID_ITEM_STATUS).setReadOnly(true);
			}			
		}
		addedItems.clear();
		modifiedItems.clear();
		removedItems.clear();
	}

	
}