/*
 * Copyright 2012 JBoss Inc
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

import java.io.IOException;

import org.kie.kieora.backend.lucene.setups.BaseLuceneSetup;
import org.kie.kieora.backend.lucene.setups.NIOLuceneSetup;

import static org.kie.kieora.backend.lucene.FileTestUtil.*;

/**
 *
 */
public class NIOLuceneSetupTest extends BaseLuceneSetupTest {

    private final NIOLuceneSetup luceneSetup;

    public NIOLuceneSetupTest() {
        try {
            this.luceneSetup = new NIOLuceneSetup( createTempDirectory() );
        } catch ( final IOException e ) {
            throw new RuntimeException( e );
        }
    }

    @Override
    protected BaseLuceneSetup getLuceneSetup() {
        return luceneSetup;
    }

}
