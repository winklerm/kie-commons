/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kieora.backend.lucene;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;
import org.kie.kieora.backend.lucene.fields.SimpleFieldFactory;
import org.kie.kieora.backend.lucene.metamodels.InMemoryMetaModelStore;
import org.kie.kieora.backend.lucene.setups.RAMLuceneSetup;
import org.kie.kieora.engine.MetaIndexEngine;
import org.kie.kieora.engine.MetaModelStore;
import org.kie.kieora.model.KObject;
import org.kie.kieora.model.KProperty;
import org.kie.kieora.model.schema.MetaType;
import org.kie.kieora.search.ClusterSegment;

import static org.junit.Assert.*;

/**
 *
 */
public class LuceneIndexSearchTest {

    @Test
    public void testSearch() {
        final FieldFactory factory = new SimpleFieldFactory();
        final MetaModelStore store = new InMemoryMetaModelStore();
        final LuceneSetup setup = new RAMLuceneSetup();
        final MetaIndexEngine engine = new LuceneIndexEngine( store, setup, factory );

        for ( int i = 0; i < 50; i++ ) {
            final int index = i;
            engine.index( new KObject() {
                @Override
                public String getId() {
                    return "unique.id.here." + index;
                }

                @Override
                public MetaType getType() {
                    return new MetaType() {
                        @Override
                        public String getName() {
                            return "Path";
                        }
                    };
                }

                @Override
                public String getClusterId() {
                    return "cluster.id.here." + index % 2;
                }

                @Override
                public String getSegmentId() {
                    return "/";
                }

                @Override
                public String getKey() {
                    return "some.key.here." + index;
                }

                @Override
                public Iterable<KProperty<?>> getProperties() {
                    return new HashSet<KProperty<?>>() {{
                        add( new KProperty<String>() {
                            @Override
                            public String getName() {
                                return "dcore.author";
                            }

                            @Override
                            public String getValue() {
                                return "Some  Author name Here" + index;
                            }

                            @Override
                            public boolean isSearchable() {
                                return true;
                            }
                        } );
                        add( new KProperty<String>() {
                            @Override
                            public String getName() {
                                return "filename";
                            }

                            @Override
                            public String getValue() {
                                return "file" + index + ".txt";
                            }

                            @Override
                            public boolean isSearchable() {
                                return true;
                            }
                        } );
                        add( new KProperty<String>() {
                            @Override
                            public String getName() {
                                return "dcore.comment";
                            }

                            @Override
                            public String getValue() {
                                return "My comment here that has some content that is important to my users." + index;
                            }

                            @Override
                            public boolean isSearchable() {
                                return true;
                            }
                        } );
                        add( new KProperty<Integer>() {
                            @Override
                            public String getName() {
                                return "dcore.review";
                            }

                            @Override
                            public Integer getValue() {
                                return 10 + index;
                            }

                            @Override
                            public boolean isSearchable() {
                                return true;
                            }
                        } );
                        add( new KProperty<Date>() {
                            @Override
                            public String getName() {
                                return "dcore.lastModifiedTime";
                            }

                            @Override
                            public Date getValue() {
                                return new Date();
                            }

                            @Override
                            public boolean isSearchable() {
                                return true;
                            }
                        } );

                    }};
                }
            } );
        }

        final LuceneSearchIndex searchEngine = new LuceneSearchIndex( setup );
//        final List<KObject> documents = new ArrayList<KObject>();

//        assertEquals( 0, searchEngine.searchByAttrsHits( null ) );
//
//        final List<KObject> initialResults = searchEngine.searchByAttrs( null, 10, 0 );
//        for ( final KObject obj : initialResults ) {
//            documents.add( obj );
//        }
//
//        for ( int i = 0; i < 4; i++ ) {
//            final List<KObject> results = searchEngine.searchByAttrs( null, 10, 10 * ( i + 1 ) );
//            assertEquals( 10, results.size() );
//            for ( final KObject result : results ) {
//                assertFalse( documents.contains( result ) );
//            }
//        }

        assertEquals( 1, searchEngine.fullTextSearchHits( "Here49" ) );
        assertEquals( 50, searchEngine.fullTextSearchHits( "comment" ) );
        assertEquals( 0, searchEngine.fullTextSearchHits( "file.txt" ) );
        assertEquals( 1, searchEngine.fullTextSearchHits( "file10" ) );
        assertEquals( 1, searchEngine.fullTextSearchHits( "\"file10.txt\"" ) );
        assertEquals( 50, searchEngine.fullTextSearchHits( "file10.txt" ) );

        assertEquals( 0, searchEngine.fullTextSearchHits( "here49", new ClusterSegment() {
            @Override
            public String getClusterId() {
                return "cluster.id.here.0";
            }

            @Override
            public String[] segmentIds() {
                return new String[]{ "/" };
            }
        } ) );

        assertEquals( 1, searchEngine.fullTextSearchHits( "here49", new ClusterSegment() {
            @Override
            public String getClusterId() {
                return "cluster.id.here.1";
            }

            @Override
            public String[] segmentIds() {
                return new String[]{ "/" };
            }
        } ) );

        assertEquals( 25, searchEngine.fullTextSearchHits( "comment", new ClusterSegment() {
            @Override
            public String getClusterId() {
                return "cluster.id.here.1";
            }

            @Override
            public String[] segmentIds() {
                return new String[]{ "/" };
            }
        } ) );

        assertEquals( 25, searchEngine.fullTextSearchHits( "comment", new ClusterSegment() {
            @Override
            public String getClusterId() {
                return "cluster.id.here.1";
            }

            @Override
            public String[] segmentIds() {
                return new String[ 0 ];
            }
        } ) );

        assertEquals( 25, searchEngine.fullTextSearchHits( "comment", new ClusterSegment() {
            @Override
            public String getClusterId() {
                return "cluster.id.here.0";
            }

            @Override
            public String[] segmentIds() {
                return new String[]{ "/" };
            }
        } ) );

        assertEquals( 25, searchEngine.fullTextSearchHits( "comment", new ClusterSegment() {
            @Override
            public String getClusterId() {
                return "cluster.id.here.0";
            }

            @Override
            public String[] segmentIds() {
                return new String[ 0 ];
            }
        } ) );

    }

}
