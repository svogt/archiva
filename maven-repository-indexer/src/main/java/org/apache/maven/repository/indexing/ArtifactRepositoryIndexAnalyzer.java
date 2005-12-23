package org.apache.maven.repository.indexing;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Reader;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharTokenizer;
import org.apache.lucene.analysis.TokenStream;

/**
 *
 * @author Edwin Punzalan
 */
public class ArtifactRepositoryIndexAnalyzer
    extends Analyzer
{
    private Analyzer defaultAnalyzer;

    public ArtifactRepositoryIndexAnalyzer( Analyzer defaultAnalyzer )
    {
        this.defaultAnalyzer = defaultAnalyzer;
    }

    public TokenStream tokenStream(String fieldName, Reader reader)
    {
        TokenStream tokenStream;

        if ( "version".equals( fieldName ) )
        {
            tokenStream = new VersionTokenizer( reader );
        }
        else
        {
            tokenStream = defaultAnalyzer.tokenStream( fieldName, reader );
        }

        return tokenStream;
    }

    private class VersionTokenizer
        extends CharTokenizer
    {
        public VersionTokenizer( Reader reader )
        {
            super( reader );
        }

        protected boolean isTokenChar(char param)
        {
            boolean token;

            switch( param )
            {
                case '.': token = false; break;
                case '-': token = false; break;
                default:
                    token = true;
            }

            return token;
        }
    }
}
