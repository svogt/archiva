package org.apache.archiva.metadata.repository.cassandra;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import me.prettyprint.cassandra.serializers.LongSerializer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.template.ColumnFamilyResult;
import me.prettyprint.cassandra.service.template.ColumnFamilyTemplate;
import me.prettyprint.cassandra.service.template.ColumnFamilyUpdater;
import me.prettyprint.cassandra.service.template.ThriftColumnFamilyTemplate;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.OrderedRows;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.exceptions.HInvalidRequestException;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.MutationResult;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.RangeSlicesQuery;
import org.apache.archiva.configuration.ArchivaConfiguration;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.model.CiManagement;
import org.apache.archiva.metadata.model.Dependency;
import org.apache.archiva.metadata.model.FacetedMetadata;
import org.apache.archiva.metadata.model.IssueManagement;
import org.apache.archiva.metadata.model.License;
import org.apache.archiva.metadata.model.MailingList;
import org.apache.archiva.metadata.model.MetadataFacet;
import org.apache.archiva.metadata.model.MetadataFacetFactory;
import org.apache.archiva.metadata.model.Organization;
import org.apache.archiva.metadata.model.ProjectMetadata;
import org.apache.archiva.metadata.model.ProjectVersionMetadata;
import org.apache.archiva.metadata.model.ProjectVersionReference;
import org.apache.archiva.metadata.model.Scm;
import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.MetadataRepositoryException;
import org.apache.archiva.metadata.repository.MetadataResolutionException;
import org.apache.archiva.metadata.repository.cassandra.model.ArtifactMetadataModel;
import org.apache.archiva.metadata.repository.cassandra.model.MetadataFacetModel;
import org.apache.archiva.metadata.repository.cassandra.model.Namespace;
import org.apache.archiva.metadata.repository.cassandra.model.Project;
import org.apache.archiva.metadata.repository.cassandra.model.ProjectVersionMetadataModel;
import org.apache.archiva.metadata.repository.cassandra.model.Repository;
import org.apache.commons.lang.StringUtils;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.apache.archiva.metadata.repository.cassandra.CassandraUtils.*;

/**
 * @author Olivier Lamy
 * @since 2.0.0
 */
public class CassandraMetadataRepository
    implements MetadataRepository
{

    private Logger logger = LoggerFactory.getLogger( getClass() );

    private ArchivaConfiguration configuration;

    private final Map<String, MetadataFacetFactory> metadataFacetFactories;

    private final CassandraArchivaManager cassandraArchivaManager;

    private final ColumnFamilyTemplate<String, String> projectVersionMetadataTemplate;

    private final ColumnFamilyTemplate<String, String> projectTemplate;

    private final ColumnFamilyTemplate<String, String> artifactMetadataTemplate;

    private final ColumnFamilyTemplate<String, String> metadataFacetTemplate;

    private final ColumnFamilyTemplate<String, String> mailingListTemplate;

    private final ColumnFamilyTemplate<String, String> licenseTemplate;

    private final ColumnFamilyTemplate<String, String> dependencyTemplate;

    private final Keyspace keyspace;

    private final StringSerializer ss = StringSerializer.get();

    public CassandraMetadataRepository( Map<String, MetadataFacetFactory> metadataFacetFactories,
                                        ArchivaConfiguration configuration,
                                        CassandraArchivaManager cassandraArchivaManager )
    {
        this.metadataFacetFactories = metadataFacetFactories;
        this.configuration = configuration;
        this.cassandraArchivaManager = cassandraArchivaManager;
        this.keyspace = cassandraArchivaManager.getKeyspace();

        this.projectVersionMetadataTemplate =
            new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                            cassandraArchivaManager.getProjectVersionMetadataFamilyName(),
                                                            StringSerializer.get(), //
                                                            StringSerializer.get() );

        this.projectTemplate = new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                                               cassandraArchivaManager.getProjectFamilyName(),
                                                                               //
                                                                               StringSerializer.get(), //
                                                                               StringSerializer.get() );

        this.artifactMetadataTemplate =
            new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                            cassandraArchivaManager.getArtifactMetadataFamilyName(),
                                                            //
                                                            StringSerializer.get(), //
                                                            StringSerializer.get() );

        this.metadataFacetTemplate =
            new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                            cassandraArchivaManager.getMetadataFacetFamilyName(),
                                                            //
                                                            StringSerializer.get(), //
                                                            StringSerializer.get() );

        this.mailingListTemplate =
            new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                            cassandraArchivaManager.getMailingListFamilyName(),
                                                            //
                                                            StringSerializer.get(), //
                                                            StringSerializer.get() );

        this.licenseTemplate = new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                                               cassandraArchivaManager.getLicenseFamilyName(),
                                                                               //
                                                                               StringSerializer.get(), //
                                                                               StringSerializer.get() );

        this.dependencyTemplate =
            new ThriftColumnFamilyTemplate<String, String>( cassandraArchivaManager.getKeyspace(), //
                                                            cassandraArchivaManager.getDependencyFamilyName(),
                                                            //
                                                            StringSerializer.get(), //
                                                            StringSerializer.get() );
    }


    /**
     * if the repository doesn't exist it will be created
     *
     * @param repositoryId
     * @return
     */
    public Repository getOrCreateRepository( String repositoryId )
        throws MetadataRepositoryException
    {
        String cf = cassandraArchivaManager.getRepositoryFamilyName();

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, StringSerializer.get(), StringSerializer.get(),
                                     StringSerializer.get() ) //
            .setColumnFamily( cf ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();

        if ( result.get().getCount() < 1 )
        {
            // we need to create the repository
            Repository repository = new Repository( repositoryId );

            try
            {
                MutationResult mutationResult = HFactory.createMutator( keyspace, StringSerializer.get() ) //
                    .addInsertion( repositoryId, cf,
                                   CassandraUtils.column( "repositoryName", repository.getName() ) ) //
                    .execute();
                return repository;
            }
            catch ( HInvalidRequestException e )
            {
                logger.error( e.getMessage(), e );
                throw new MetadataRepositoryException( e.getMessage(), e );
            }

        }

        return new Repository(
            result.get().getList().get( 0 ).getColumnSlice().getColumnByName( "repositoryName" ).getValue() );
    }


    protected Repository getRepository( String repositoryId )
        throws MetadataRepositoryException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, StringSerializer.get(), StringSerializer.get(),
                                     StringSerializer.get() ) //
            .setColumnFamily( cassandraArchivaManager.getRepositoryFamilyName() ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();
        return ( result.get().getCount() > 0 ) ? new Repository( repositoryId ) : null;
    }

    @Override
    public void updateNamespace( String repositoryId, String namespaceId )
        throws MetadataRepositoryException
    {
        updateOrAddNamespace( repositoryId, namespaceId );
    }

    private Namespace updateOrAddNamespace( String repositoryId, String namespaceId )
        throws MetadataRepositoryException
    {
        try
        {
            Repository repository = getOrCreateRepository( repositoryId );

            String key =
                new Namespace.KeyBuilder().withNamespace( namespaceId ).withRepositoryId( repositoryId ).build();

            Namespace namespace = getNamespace( repositoryId, namespaceId );
            if ( namespace == null )
            {
                String cf = cassandraArchivaManager.getNamespaceFamilyName();
                namespace = new Namespace( namespaceId, repository );
                HFactory.createMutator( keyspace, StringSerializer.get() )
                    //  values
                    .addInsertion( key, cf, CassandraUtils.column( "name", namespace.getName() ) ) //
                    .addInsertion( key, cf, CassandraUtils.column( "repositoryName", repository.getName() ) ) //
                    .execute();
            }

            return namespace;
        }
        catch ( HInvalidRequestException e )
        {
            logger.error( e.getMessage(), e );
            throw new MetadataRepositoryException( e.getMessage(), e );
        }
    }

    protected Namespace getNamespace( String repositoryId, String namespaceId )
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "repositoryName", "name" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "name", namespaceId ) //
            .execute();
        if ( result.get().getCount() > 0 )
        {
            ColumnSlice<String, String> columnSlice = result.get().getList().get( 0 ).getColumnSlice();
            return new Namespace( getStringValue( columnSlice, "name" ), //
                                  new Repository( getStringValue( columnSlice, "repositoryName" ) ) );

        }
        return null;
    }


    @Override
    public void removeNamespace( String repositoryId, String namespaceId )
        throws MetadataRepositoryException
    {

        try
        {
            String key = new Namespace.KeyBuilder() //
                .withNamespace( namespaceId ) //
                .withRepositoryId( repositoryId ) //
                .build();

            HFactory.createMutator( cassandraArchivaManager.getKeyspace(), new StringSerializer() ) //
                .addDeletion( key, cassandraArchivaManager.getNamespaceFamilyName() ) //
                .execute();

            QueryResult<OrderedRows<String, String, String>> result = HFactory //
                .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
                .setColumnNames( "repositoryName" ) //
                .addEqualsExpression( "repositoryName", repositoryId ) //
                .addEqualsExpression( "namespaceId", namespaceId ) //
                .execute();

            for ( Row<String, String, String> row : result.get() )
            {
                this.projectTemplate.deleteRow( row.getKey() );
            }

            result = HFactory //
                .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
                .setColumnNames( "repositoryName" ) //
                .addEqualsExpression( "repositoryName", repositoryId ) //
                .addEqualsExpression( "namespaceId", namespaceId ) //
                .execute();

            for ( Row<String, String, String> row : result.get() )
            {
                this.projectVersionMetadataTemplate.deleteRow( row.getKey() );
                removeMailingList( row.getKey() );
            }

            result = HFactory //
                .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
                .setColumnNames( "repositoryName" ) //
                .addEqualsExpression( "repositoryName", repositoryId ) //
                .addEqualsExpression( "namespaceId", namespaceId ) //
                .execute();

            for ( Row<String, String, String> row : result.get() )
            {
                this.artifactMetadataTemplate.deleteRow( row.getKey() );
            }

            result = HFactory //
                .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
                .setColumnNames( "repositoryName" ) //
                .addEqualsExpression( "repositoryName", repositoryId ) //
                .addEqualsExpression( "namespaceId", namespaceId ) //
                .execute();

            for ( Row<String, String, String> row : result.get() )
            {
                this.metadataFacetTemplate.deleteRow( row.getKey() );
            }

        }
        catch ( HInvalidRequestException e )
        {
            logger.error( e.getMessage(), e );
            throw new MetadataRepositoryException( e.getMessage(), e );
        }
    }


    @Override
    public void removeRepository( final String repositoryId )
        throws MetadataRepositoryException
    {

        // TODO use cql queries to delete all
        List<String> namespacesKey = new ArrayList<>();

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();

        for ( Row<String, String, String> row : result.get().getList() )
        {
            namespacesKey.add( row.getKey() );
        }

        HFactory.createMutator( cassandraArchivaManager.getKeyspace(), ss ) //
            .addDeletion( namespacesKey, cassandraArchivaManager.getNamespaceFamilyName() ) //
            .execute();

        //delete repositoryId
        HFactory.createMutator( cassandraArchivaManager.getKeyspace(), ss ) //
            .addDeletion( repositoryId, cassandraArchivaManager.getRepositoryFamilyName() ) //
            .execute();

        result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.projectTemplate.deleteRow( row.getKey() );
        }

        result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.projectVersionMetadataTemplate.deleteRow( row.getKey() );
            removeMailingList( row.getKey() );
        }

        result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.artifactMetadataTemplate.deleteRow( row.getKey() );
        }

        result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "repositoryName" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.metadataFacetTemplate.deleteRow( row.getKey() );
        }


    }

    @Override
    public Collection<String> getRepositories()
        throws MetadataRepositoryException
    {
        try
        {
            logger.debug( "getRepositories" );

            final QueryResult<OrderedRows<String, String, String>> cResult = //
                HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), //
                                                 ss, ss, ss ) //
                    .setColumnFamily( cassandraArchivaManager.getRepositoryFamilyName() ) //
                    .setColumnNames( "repositoryName" ) //
                    .setRange( null, null, false, Integer.MAX_VALUE ) //
                    .execute();

            List<String> repoIds = new ArrayList<>( cResult.get().getCount() );

            for ( Row<String, String, String> row : cResult.get() )
            {
                repoIds.add( getStringValue( row.getColumnSlice(), "repositoryName" ) );
            }

            return repoIds;
        }
        catch ( PersistenceException e )
        {
            throw new MetadataRepositoryException( e.getMessage(), e );
        }

    }

    // FIXME this one need peformance improvement maybe a cache?
    @Override
    public Collection<String> getRootNamespaces( final String repoId )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .execute();

        Set<String> namespaces = new HashSet<String>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            namespaces.add( StringUtils.substringBefore( getStringValue( row.getColumnSlice(), "name" ), "." ) );
        }

        return namespaces;
    }

    // FIXME this one need peformance improvement maybe a cache?
    @Override
    public Collection<String> getNamespaces( final String repoId, final String namespaceId )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .execute();

        List<String> namespaces = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            String currentNamespace = getStringValue( row.getColumnSlice(), "name" );
            if ( StringUtils.startsWith( currentNamespace, namespaceId ) //
                && ( StringUtils.length( currentNamespace ) > StringUtils.length( namespaceId ) ) )
            {
                // store after namespaceId '.' but before next '.'
                // call org namespace org.apache.maven.shared -> stored apache

                String calledNamespace = StringUtils.endsWith( namespaceId, "." ) ? namespaceId : namespaceId + ".";
                String storedNamespace = StringUtils.substringAfter( currentNamespace, calledNamespace );

                storedNamespace = StringUtils.substringBefore( storedNamespace, "." );

                namespaces.add( storedNamespace );
            }
        }

        return namespaces;

    }

    // only use for testing purpose
    protected List<String> getNamespaces( final String repoId )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getNamespaceFamilyName() ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .execute();

        List<String> namespaces = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            namespaces.add( getStringValue( row.getColumnSlice(), "name" ) );
        }

        return namespaces;
    }


    @Override
    public void updateProject( String repositoryId, ProjectMetadata projectMetadata )
        throws MetadataRepositoryException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
            .setColumnNames( "projectId" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "namespaceId", projectMetadata.getNamespace() ) //
            .addEqualsExpression( "projectId", projectMetadata.getId() ) //
            .execute();

        // project exists ? if yes return nothing to update here
        if ( result.get().getCount() > 0 )
        {
            return;
        }
        else
        {
            Namespace namespace = updateOrAddNamespace( repositoryId, projectMetadata.getNamespace() );

            String key =
                new Project.KeyBuilder().withProjectId( projectMetadata.getId() ).withNamespace( namespace ).build();

            String cf = cassandraArchivaManager.getProjectFamilyName();
            projectTemplate.createMutator()
                //  values
                .addInsertion( key, cf, CassandraUtils.column( "projectId", projectMetadata.getId() ) ) //
                .addInsertion( key, cf, CassandraUtils.column( "repositoryName", repositoryId ) ) //
                .addInsertion( key, cf, CassandraUtils.column( "namespaceId", projectMetadata.getNamespace() ) )//
                .execute();
        }
    }

    @Override
    public Collection<String> getProjects( final String repoId, final String namespace )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
            .setColumnNames( "projectId" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .execute();

        final Set<String> projects = new HashSet<String>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            projects.add( getStringValue( row.getColumnSlice(), "projectId" ) );
        }

        return projects;
    }

    @Override
    public void removeProject( final String repositoryId, final String namespaceId, final String projectId )
        throws MetadataRepositoryException
    {

        String key = new Project.KeyBuilder() //
            .withProjectId( projectId ) //
            .withNamespace( new Namespace( namespaceId, new Repository( repositoryId ) ) ) //
            .build();

        this.projectTemplate.deleteRow( key );

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "id" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "namespaceId", namespaceId ) //
            .addEqualsExpression( "projectId", projectId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.projectVersionMetadataTemplate.deleteRow( row.getKey() );
            removeMailingList( row.getKey() );
        }

        result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "projectId" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "namespaceId", namespaceId ) //
            .addEqualsExpression( "projectId", projectId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.artifactMetadataTemplate.deleteRow( row.getKey() );
        }
    }

    @Override
    public Collection<String> getProjectVersions( final String repoId, final String namespace, final String projectId )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "projectVersion" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", projectId ) //
            .execute();

        int count = result.get().getCount();

        if ( count < 1 )
        {
            return Collections.emptyList();
        }

        Set<String> versions = new HashSet<String>( count );

        for ( Row<String, String, String> orderedRows : result.get() )
        {
            versions.add( getStringValue( orderedRows.getColumnSlice(), "projectVersion" ) );
        }

        return versions;

    }

    @Override
    public ProjectMetadata getProject( final String repoId, final String namespace, final String id )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectFamilyName() ) //
            .setColumnNames( "projectId" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", id ) //
            .execute();

        int count = result.get().getCount();

        if ( count < 1 )
        {
            return null;
        }

        ProjectMetadata projectMetadata = new ProjectMetadata();
        projectMetadata.setId( id );
        projectMetadata.setNamespace( namespace );

        logger.debug( "getProject repoId: {}, namespace: {}, projectId: {} -> {}", repoId, namespace, id,
                      projectMetadata );

        return projectMetadata;
    }

    protected ProjectVersionMetadataModel mapProjectVersionMetadataModel( ColumnSlice<String, String> columnSlice )
    {
        ProjectVersionMetadataModel projectVersionMetadataModel = new ProjectVersionMetadataModel();
        projectVersionMetadataModel.setId( getStringValue( columnSlice, "id" ) );
        projectVersionMetadataModel.setDescription( getStringValue( columnSlice, "description" ) );
        projectVersionMetadataModel.setName( getStringValue( columnSlice, "name" ) );
        Namespace namespace = new Namespace( getStringValue( columnSlice, "namespaceId" ), //
                                             new Repository( getStringValue( columnSlice, "repositoryName" ) ) );
        projectVersionMetadataModel.setNamespace( namespace );
        projectVersionMetadataModel.setIncomplete(
            Boolean.parseBoolean( getStringValue( columnSlice, "incomplete" ) ) );
        projectVersionMetadataModel.setProjectId( getStringValue( columnSlice, "projectId" ) );
        projectVersionMetadataModel.setUrl( getStringValue( columnSlice, "url" ) );
        return projectVersionMetadataModel;
    }


    @Override
    public void updateProjectVersion( String repositoryId, String namespaceId, String projectId,
                                      ProjectVersionMetadata versionMetadata )
        throws MetadataRepositoryException
    {
        try
        {
            Namespace namespace = getNamespace( repositoryId, namespaceId );

            if ( namespace == null )
            {
                updateOrAddNamespace( repositoryId, namespaceId );
            }

            if ( getProject( repositoryId, namespaceId, projectId ) == null )
            {
                ProjectMetadata projectMetadata = new ProjectMetadata();
                projectMetadata.setNamespace( namespaceId );
                projectMetadata.setId( projectId );
                updateProject( repositoryId, projectMetadata );
            }

        }
        catch ( MetadataResolutionException e )
        {
            throw new MetadataRepositoryException( e.getMessage(), e );
        }

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "projectVersion" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "namespaceId", namespaceId ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", versionMetadata.getId() ) //
            .execute();

        ProjectVersionMetadataModel projectVersionMetadataModel = null;
        boolean creation = true;
        if ( result.get().getCount() > 0 )
        {
            projectVersionMetadataModel =
                mapProjectVersionMetadataModel( result.get().getList().get( 0 ).getColumnSlice() );
            creation = false;
        }
        else
        {
            projectVersionMetadataModel = getModelMapper().map( versionMetadata, ProjectVersionMetadataModel.class );
        }

        projectVersionMetadataModel.setProjectId( projectId );
        projectVersionMetadataModel.setNamespace( new Namespace( namespaceId, new Repository( repositoryId ) ) );

        projectVersionMetadataModel.setCiManagement( versionMetadata.getCiManagement() );
        projectVersionMetadataModel.setIssueManagement( versionMetadata.getIssueManagement() );
        projectVersionMetadataModel.setOrganization( versionMetadata.getOrganization() );
        projectVersionMetadataModel.setScm( versionMetadata.getScm() );

        projectVersionMetadataModel.setMailingLists( versionMetadata.getMailingLists() );
        projectVersionMetadataModel.setDependencies( versionMetadata.getDependencies() );
        projectVersionMetadataModel.setLicenses( versionMetadata.getLicenses() );

        // we don't test of repository and namespace really exist !
        String key = new ProjectVersionMetadataModel.KeyBuilder() //
            .withRepository( repositoryId ) //
            .withNamespace( namespaceId ) //
            .withProjectId( projectId ) //
            .withProjectVersion( versionMetadata.getVersion() ) //
            .withId( versionMetadata.getId() ) //
            .build();

        // FIXME nested objects to store!!!
        if ( creation )
        {
            String cf = cassandraArchivaManager.getProjectVersionMetadataFamilyName();
            Mutator<String> mutator = projectVersionMetadataTemplate.createMutator()
                //  values
                .addInsertion( key, cf, column( "projectId", projectId ) ) //
                .addInsertion( key, cf, column( "repositoryName", repositoryId ) ) //
                .addInsertion( key, cf, column( "namespaceId", namespaceId ) )//
                .addInsertion( key, cf, column( "projectVersion", versionMetadata.getVersion() ) ); //

            addInsertion( mutator, key, cf, "description", versionMetadata.getDescription() );

            addInsertion( mutator, key, cf, "name", versionMetadata.getName() );

            addInsertion( mutator, key, cf, "incomplete", Boolean.toString( versionMetadata.isIncomplete() ) );

            addInsertion( mutator, key, cf, "url", versionMetadata.getUrl() );
            {
                CiManagement ci = versionMetadata.getCiManagement();
                if ( ci != null )
                {
                    addInsertion( mutator, key, cf, "ciManagement.system", ci.getSystem() );
                    addInsertion( mutator, key, cf, "ciManagement.url", ci.getUrl() );
                }
            }

            {
                IssueManagement issueManagement = versionMetadata.getIssueManagement();

                if ( issueManagement != null )
                {
                    addInsertion( mutator, key, cf, "issueManagement.system", issueManagement.getSystem() );
                    addInsertion( mutator, key, cf, "issueManagement.url", issueManagement.getUrl() );
                }
            }

            {
                Organization organization = versionMetadata.getOrganization();
                if ( organization != null )
                {
                    addInsertion( mutator, key, cf, "organization.name", organization.getName() );
                    addInsertion( mutator, key, cf, "organization.url", organization.getUrl() );
                }
            }

            {
                Scm scm = versionMetadata.getScm();
                if ( scm != null )
                {
                    addInsertion( mutator, key, cf, "scm.url", scm.getUrl() );
                    addInsertion( mutator, key, cf, "scm.connection", scm.getConnection() );
                    addInsertion( mutator, key, cf, "scm.developerConnection", scm.getDeveloperConnection() );
                }
            }

            recordMailingList( key, versionMetadata.getMailingLists() );

            recordLicenses( key, versionMetadata.getLicenses() );

            recordDependencies( key, versionMetadata.getDependencies(), repositoryId );

            MutationResult mutationResult = mutator.execute();
        }
        else
        {
            ColumnFamilyUpdater<String, String> updater = projectVersionMetadataTemplate.createUpdater( key );
            addUpdateStringValue( updater, "projectId", projectId );
            addUpdateStringValue( updater, "repositoryName", repositoryId );
            addUpdateStringValue( updater, "namespaceId", namespaceId );
            addUpdateStringValue( updater, "projectVersion", versionMetadata.getVersion() );
            addUpdateStringValue( updater, "description", versionMetadata.getDescription() );

            addUpdateStringValue( updater, "name", versionMetadata.getName() );

            updater.setString( "incomplete", Boolean.toString( versionMetadata.isIncomplete() ) );
            addUpdateStringValue( updater, "url", versionMetadata.getUrl() );

            {
                CiManagement ci = versionMetadata.getCiManagement();
                if ( ci != null )
                {
                    addUpdateStringValue( updater, "ciManagement.system", ci.getSystem() );
                    addUpdateStringValue( updater, "ciManagement.url", ci.getUrl() );
                }
            }
            {
                IssueManagement issueManagement = versionMetadata.getIssueManagement();
                if ( issueManagement != null )
                {
                    addUpdateStringValue( updater, "issueManagement.system", issueManagement.getSystem() );
                    addUpdateStringValue( updater, "issueManagement.url", issueManagement.getUrl() );
                }
            }
            {
                Organization organization = versionMetadata.getOrganization();
                if ( organization != null )
                {
                    addUpdateStringValue( updater, "organization.name", organization.getName() );
                    addUpdateStringValue( updater, "organization.url", organization.getUrl() );
                }
            }
            {
                Scm scm = versionMetadata.getScm();
                if ( scm != null )
                {
                    addUpdateStringValue( updater, "scm.url", scm.getUrl() );
                    addUpdateStringValue( updater, "scm.connection", scm.getConnection() );
                    addUpdateStringValue( updater, "scm.developerConnection", scm.getDeveloperConnection() );
                }
            }

            // update is a delete record
            removeMailingList( key );
            recordMailingList( key, versionMetadata.getMailingLists() );

            removeLicenses( key );
            recordLicenses( key, versionMetadata.getLicenses() );

            removeDependencies( key );
            recordDependencies( key, versionMetadata.getDependencies(), repositoryId );

            projectVersionMetadataTemplate.update( updater );

        }

        ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();
        artifactMetadataModel.setRepositoryId( repositoryId );
        artifactMetadataModel.setNamespace( namespaceId );
        artifactMetadataModel.setProject( projectId );
        artifactMetadataModel.setProjectVersion( versionMetadata.getVersion() );
        artifactMetadataModel.setVersion( versionMetadata.getVersion() );
        updateFacets( versionMetadata, artifactMetadataModel );

    }


    @Override
    public ProjectVersionMetadata getProjectVersion( final String repoId, final String namespace,
                                                     final String projectId, final String projectVersion )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "projectVersion" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion ) //
            .execute();

        if ( result.get().getCount() < 1 )
        {
            return null;
        }

        String key = result.get().iterator().next().getKey();

        ColumnFamilyResult<String, String> columnFamilyResult = this.projectVersionMetadataTemplate.queryColumns( key );

        if ( !columnFamilyResult.hasResults() )
        {
            return null;
        }

        ProjectVersionMetadata projectVersionMetadata = new ProjectVersionMetadata();
        projectVersionMetadata.setId( columnFamilyResult.getString( "projectVersion" ) );
        projectVersionMetadata.setDescription( columnFamilyResult.getString( "description" ) );
        projectVersionMetadata.setName( columnFamilyResult.getString( "name" ) );

        projectVersionMetadata.setIncomplete( Boolean.parseBoolean( columnFamilyResult.getString( "incomplete" ) ) );

        projectVersionMetadata.setUrl( columnFamilyResult.getString( "url" ) );
        {
            String ciUrl = columnFamilyResult.getString( "ciManagement.url" );
            String ciSystem = columnFamilyResult.getString( "ciManagement.system" );

            if ( StringUtils.isNotEmpty( ciSystem ) || StringUtils.isNotEmpty( ciUrl ) )
            {
                projectVersionMetadata.setCiManagement( new CiManagement( ciSystem, ciUrl ) );
            }
        }
        {
            String issueUrl = columnFamilyResult.getString( "issueManagement.url" );
            String issueSystem = columnFamilyResult.getString( "issueManagement.system" );
            if ( StringUtils.isNotEmpty( issueSystem ) || StringUtils.isNotEmpty( issueUrl ) )
            {
                projectVersionMetadata.setIssueManagement( new IssueManagement( issueSystem, issueUrl ) );
            }
        }
        {
            String organizationUrl = columnFamilyResult.getString( "organization.url" );
            String organizationName = columnFamilyResult.getString( "organization.name" );
            if ( StringUtils.isNotEmpty( organizationUrl ) || StringUtils.isNotEmpty( organizationName ) )
            {
                projectVersionMetadata.setOrganization( new Organization( organizationName, organizationUrl ) );
            }
        }
        {
            String devConn = columnFamilyResult.getString( "scm.developerConnection" );
            String conn = columnFamilyResult.getString( "scm.connection" );
            String url = columnFamilyResult.getString( "scm.url" );
            if ( StringUtils.isNotEmpty( devConn ) || StringUtils.isNotEmpty( conn ) || StringUtils.isNotEmpty( url ) )
            {
                projectVersionMetadata.setScm( new Scm( conn, devConn, url ) );
            }
        }
        projectVersionMetadata.setMailingLists( getMailingLists( key ) );
        projectVersionMetadata.setLicenses( getLicenses( key ) );
        projectVersionMetadata.setDependencies( getDependencies( key ) );
        // facets

        result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "facetId", "key", "value", "name" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion ) //
            .execute();

        Map<String, Map<String, String>> metadataFacetsPerFacetIds = new HashMap<>();

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();
            String facetId = getStringValue( columnSlice, "facetId" );
            Map<String, String> metaValues = metadataFacetsPerFacetIds.get( facetId );
            if ( metaValues == null )
            {
                metaValues = new HashMap<>();
                metadataFacetsPerFacetIds.put( facetId, metaValues );
            }
            metaValues.put( getStringValue( columnSlice, "key" ), getStringValue( columnSlice, "value" ) );
        }

        if ( !metadataFacetsPerFacetIds.isEmpty() )
        {
            for ( Map.Entry<String, Map<String, String>> entry : metadataFacetsPerFacetIds.entrySet() )
            {
                MetadataFacetFactory metadataFacetFactory = metadataFacetFactories.get( entry.getKey() );
                if ( metadataFacetFactory != null )
                {
                    MetadataFacet metadataFacet = metadataFacetFactory.createMetadataFacet();
                    metadataFacet.fromProperties( entry.getValue() );
                    projectVersionMetadata.addFacet( metadataFacet );
                }
            }
        }

        return projectVersionMetadata;
    }

    protected void recordMailingList( String projectVersionMetadataKey, List<MailingList> mailingLists )
    {
        if ( mailingLists == null || mailingLists.isEmpty() )
        {
            return;
        }
        Mutator<String> mailingMutator = this.mailingListTemplate.createMutator();
        for ( MailingList mailingList : mailingLists )
        {
            // we don't care about the key as the real used one with the projectVersionMetadata
            String keyMailingList = UUID.randomUUID().toString();
            String cfMailingList = cassandraArchivaManager.getMailingListFamilyName();

            addInsertion( mailingMutator, keyMailingList, cfMailingList, "projectVersionMetadataModel.key",
                          projectVersionMetadataKey );
            addInsertion( mailingMutator, keyMailingList, cfMailingList, "name", mailingList.getName() );
            addInsertion( mailingMutator, keyMailingList, cfMailingList, "mainArchiveUrl",
                          mailingList.getMainArchiveUrl() );
            addInsertion( mailingMutator, keyMailingList, cfMailingList, "postAddress", mailingList.getPostAddress() );
            addInsertion( mailingMutator, keyMailingList, cfMailingList, "subscribeAddress",
                          mailingList.getSubscribeAddress() );
            addInsertion( mailingMutator, keyMailingList, cfMailingList, "unsubscribeAddress",
                          mailingList.getUnsubscribeAddress() );
            int idx = 0;
            for ( String otherArchive : mailingList.getOtherArchives() )
            {
                addInsertion( mailingMutator, keyMailingList, cfMailingList, "otherArchive." + idx, otherArchive );
                idx++;
            }

        }
        mailingMutator.execute();
    }

    protected void removeMailingList( String projectVersionMetadataKey )
    {

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getMailingListFamilyName() ) //
                .setColumnNames( "name" ) //
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "projectVersionMetadataModel.key", projectVersionMetadataKey ) //
                .execute();

        if ( result.get().getCount() < 1 )
        {
            return;
        }

        for ( Row<String, String, String> row : result.get() )
        {
            this.mailingListTemplate.deleteRow( row.getKey() );
        }

    }

    protected List<MailingList> getMailingLists( String projectVersionMetadataKey )
    {
        List<MailingList> mailingLists = new ArrayList<>();

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getMailingListFamilyName() ) //
                .setColumnNames( "name" ) //
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "projectVersionMetadataModel.key", projectVersionMetadataKey ) //
                .execute();
        for ( Row<String, String, String> row : result.get() )
        {
            ColumnFamilyResult<String, String> columnFamilyResult =
                this.mailingListTemplate.queryColumns( row.getKey() );

            MailingList mailingList = new MailingList();
            mailingList.setName( columnFamilyResult.getString( "name" ) );
            mailingList.setMainArchiveUrl( columnFamilyResult.getString( "mainArchiveUrl" ) );
            mailingList.setPostAddress( columnFamilyResult.getString( "postAddress" ) );
            mailingList.setSubscribeAddress( columnFamilyResult.getString( "subscribeAddress" ) );
            mailingList.setUnsubscribeAddress( columnFamilyResult.getString( "unsubscribeAddress" ) );

            List<String> otherArchives = new ArrayList<>();

            for ( String columnName : columnFamilyResult.getColumnNames() )
            {
                if ( StringUtils.startsWith( columnName, "otherArchive." ) )
                {
                    otherArchives.add( columnFamilyResult.getString( columnName ) );
                }
            }

            mailingList.setOtherArchives( otherArchives );
            mailingLists.add( mailingList );
        }

        return mailingLists;
    }

    protected void recordLicenses( String projectVersionMetadataKey, List<License> licenses )
    {

        if ( licenses == null || licenses.isEmpty() )
        {
            return;
        }
        Mutator<String> licenseMutator = this.licenseTemplate.createMutator();

        for ( License license : licenses )
        {
            // we don't care about the key as the real used one with the projectVersionMetadata
            String keyLicense = UUID.randomUUID().toString();
            String cfLicense = cassandraArchivaManager.getLicenseFamilyName();

            addInsertion( licenseMutator, keyLicense, cfLicense, "projectVersionMetadataModel.key",
                          projectVersionMetadataKey );

            addInsertion( licenseMutator, keyLicense, cfLicense, "name", license.getName() );

            addInsertion( licenseMutator, keyLicense, cfLicense, "url", license.getUrl() );

        }
        licenseMutator.execute();
    }

    protected void removeLicenses( String projectVersionMetadataKey )
    {

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getLicenseFamilyName() ) //
                .setColumnNames( "name" ) //
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "projectVersionMetadataModel.key", projectVersionMetadataKey ) //
                .execute();
        for ( Row<String, String, String> row : result.get() )
        {
            this.licenseTemplate.deleteRow( row.getKey() );
        }
    }

    protected List<License> getLicenses( String projectVersionMetadataKey )
    {
        List<License> licenses = new ArrayList<>();

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getLicenseFamilyName() ) //
                .setColumnNames( "projectVersionMetadataModel.key" ) //
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "projectVersionMetadataModel.key", projectVersionMetadataKey ) //
                .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnFamilyResult<String, String> columnFamilyResult = this.licenseTemplate.queryColumns( row.getKey() );

            licenses.add(
                new License( columnFamilyResult.getString( "name" ), columnFamilyResult.getString( "url" ) ) );
        }

        return licenses;
    }


    protected void recordDependencies( String projectVersionMetadataKey, List<Dependency> dependencies,
                                       String repositoryId )
    {

        if ( dependencies == null || dependencies.isEmpty() )
        {
            return;
        }
        Mutator<String> dependencyMutator = this.dependencyTemplate.createMutator();

        for ( Dependency dependency : dependencies )
        {
            // we don't care about the key as the real used one with the projectVersionMetadata
            String keyDependency = UUID.randomUUID().toString();
            String cfDependency = cassandraArchivaManager.getDependencyFamilyName();

            addInsertion( dependencyMutator, keyDependency, cfDependency, "projectVersionMetadataModel.key",
                          projectVersionMetadataKey );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "repositoryName", repositoryId );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "classifier", dependency.getClassifier() );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "optional",
                          Boolean.toString( dependency.isOptional() ) );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "scope", dependency.getScope() );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "systemPath", dependency.getSystemPath() );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "type", dependency.getType() );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "artifactId", dependency.getArtifactId() );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "groupId", dependency.getGroupId() );

            addInsertion( dependencyMutator, keyDependency, cfDependency, "version", dependency.getVersion() );

        }
        dependencyMutator.execute();
    }

    protected void removeDependencies( String projectVersionMetadataKey )
    {

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getDependencyFamilyName() ) //
                .setColumnNames( "groupId" ) //
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "projectVersionMetadataModel.key", projectVersionMetadataKey ) //
                .execute();
        for ( Row<String, String, String> row : result.get() )
        {
            this.dependencyTemplate.deleteRow( row.getKey() );
        }
    }

    protected List<Dependency> getDependencies( String projectVersionMetadataKey )
    {
        List<Dependency> dependencies = new ArrayList<>();

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( cassandraArchivaManager.getKeyspace(), ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getDependencyFamilyName() ) //
                .setColumnNames( "projectVersionMetadataModel.key" ) //
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "projectVersionMetadataModel.key", projectVersionMetadataKey ) //
                .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnFamilyResult<String, String> columnFamilyResult =
                this.dependencyTemplate.queryColumns( row.getKey() );

            Dependency dependency = new Dependency();
            dependency.setClassifier( columnFamilyResult.getString( "classifier" ) );

            dependency.setOptional( Boolean.parseBoolean( columnFamilyResult.getString( "optional" ) ) );

            dependency.setScope( columnFamilyResult.getString( "scope" ) );

            dependency.setSystemPath( columnFamilyResult.getString( "systemPath" ) );

            dependency.setType( columnFamilyResult.getString( "type" ) );

            dependency.setArtifactId( columnFamilyResult.getString( "artifactId" ) );

            dependency.setGroupId( columnFamilyResult.getString( "groupId" ) );

            dependency.setVersion( columnFamilyResult.getString( "version" ) );

            dependencies.add( dependency );
        }

        return dependencies;
    }

    @Override
    public void updateArtifact( String repositoryId, String namespaceId, String projectId, String projectVersion,
                                ArtifactMetadata artifactMeta )
        throws MetadataRepositoryException
    {

        Namespace namespace = getNamespace( repositoryId, namespaceId );
        if ( namespace == null )
        {
            namespace = updateOrAddNamespace( repositoryId, namespaceId );
        }

        ProjectMetadata projectMetadata = new ProjectMetadata();
        projectMetadata.setId( projectId );
        projectMetadata.setNamespace( namespaceId );
        updateProject( repositoryId, projectMetadata );

        String key = new ArtifactMetadataModel.KeyBuilder().withNamespace( namespace ).withProject( projectId ).withId(
            artifactMeta.getId() ).withProjectVersion( projectVersion ).build();

        // exists?

        boolean exists = this.artifactMetadataTemplate.isColumnsExist( key );

        if ( exists )
        {
            // updater
            ColumnFamilyUpdater<String, String> updater = this.artifactMetadataTemplate.createUpdater( key );
            updater.setLong( "fileLastModified", artifactMeta.getFileLastModified().getTime() );
            updater.setLong( "whenGathered", artifactMeta.getWhenGathered().getTime() );
            updater.setLong( "size", artifactMeta.getSize() );
            addUpdateStringValue( updater, "md5", artifactMeta.getMd5() );
            addUpdateStringValue( updater, "sha1", artifactMeta.getSha1() );
            addUpdateStringValue( updater, "version", artifactMeta.getVersion() );
            this.artifactMetadataTemplate.update( updater );
        }
        else
        {
            String cf = this.cassandraArchivaManager.getArtifactMetadataFamilyName();
            // create
            this.artifactMetadataTemplate.createMutator() //
                .addInsertion( key, cf, column( "id", artifactMeta.getId() ) )//
                .addInsertion( key, cf, column( "repositoryName", repositoryId ) ) //
                .addInsertion( key, cf, column( "namespaceId", namespaceId ) ) //
                .addInsertion( key, cf, column( "project", artifactMeta.getProject() ) ) //
                .addInsertion( key, cf, column( "projectVersion", projectVersion ) ) //
                .addInsertion( key, cf, column( "version", artifactMeta.getVersion() ) ) //
                .addInsertion( key, cf, column( "fileLastModified", artifactMeta.getFileLastModified().getTime() ) ) //
                .addInsertion( key, cf, column( "size", artifactMeta.getSize() ) ) //
                .addInsertion( key, cf, column( "md5", artifactMeta.getMd5() ) ) //
                .addInsertion( key, cf, column( "sha1", artifactMeta.getSha1() ) ) //
                .addInsertion( key, cf, column( "whenGathered", artifactMeta.getWhenGathered().getTime() ) )//
                .execute();
        }

        key = new ProjectVersionMetadataModel.KeyBuilder() //
            .withRepository( repositoryId ) //
            .withNamespace( namespace ) //
            .withProjectId( projectId ) //
            .withProjectVersion( projectVersion ) //
            .withId( artifactMeta.getId() ) //
            .build();

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "version" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "namespaceId", namespaceId ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion ) //
            .addEqualsExpression( "version", artifactMeta.getVersion() ) //
            .execute();

        exists = result.get().getCount() > 0;

        if ( !exists )
        {
            String cf = this.cassandraArchivaManager.getProjectVersionMetadataFamilyName();

            projectVersionMetadataTemplate.createMutator() //
                .addInsertion( key, cf, column( "namespaceId", namespace.getName() ) ) //
                .addInsertion( key, cf, column( "repositoryName", repositoryId ) ) //
                .addInsertion( key, cf, column( "projectVersion", projectVersion ) ) //
                .addInsertion( key, cf, column( "projectId", projectId ) ) //
                .addInsertion( key, cf, column( "version", artifactMeta.getVersion() ) ) //
                .execute();

        }

        ArtifactMetadataModel artifactMetadataModel = new ArtifactMetadataModel();

        artifactMetadataModel.setRepositoryId( repositoryId );
        artifactMetadataModel.setNamespace( namespaceId );
        artifactMetadataModel.setProject( projectId );
        artifactMetadataModel.setProjectVersion( projectVersion );
        artifactMetadataModel.setVersion( artifactMeta.getVersion() );
        artifactMetadataModel.setFileLastModified( artifactMeta.getFileLastModified() == null
                                                       ? new Date().getTime()
                                                       : artifactMeta.getFileLastModified().getTime() );

        // now facets
        updateFacets( artifactMeta, artifactMetadataModel );

    }

    @Override
    public Collection<String> getArtifactVersions( final String repoId, final String namespace, final String projectId,
                                                   final String projectVersion )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "version" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion ) //
            .execute();

        final Set<String> versions = new HashSet<String>();

        for ( Row<String, String, String> row : result.get() )
        {
            versions.add( getStringValue( row.getColumnSlice(), "version" ) );
        }

        return versions;

    }

    /**
     * iterate over available facets to remove/add from the artifactMetadata
     *
     * @param facetedMetadata
     * @param artifactMetadataModel only use for the key
     */
    private void updateFacets( final FacetedMetadata facetedMetadata,
                               final ArtifactMetadataModel artifactMetadataModel )
    {

        String cf = cassandraArchivaManager.getMetadataFacetFamilyName();

        for ( final String facetId : metadataFacetFactories.keySet() )
        {
            MetadataFacet metadataFacet = facetedMetadata.getFacet( facetId );
            if ( metadataFacet == null )
            {
                continue;
            }
            // clean first

            QueryResult<OrderedRows<String, String, String>> result =
                HFactory.createRangeSlicesQuery( keyspace, ss, ss, ss ) //
                    .setColumnFamily( cf ) //
                    .setColumnNames( "repositoryName" ) //
                    .addEqualsExpression( "repositoryName", artifactMetadataModel.getRepositoryId() ) //
                    .addEqualsExpression( "namespaceId", artifactMetadataModel.getNamespace() ) //
                    .addEqualsExpression( "projectId", artifactMetadataModel.getProject() ) //
                    .addEqualsExpression( "projectVersion", artifactMetadataModel.getProjectVersion() ) //
                    .addEqualsExpression( "facetId", facetId ) //
                    .execute();

            for ( Row<String, String, String> row : result.get().getList() )
            {
                this.metadataFacetTemplate.deleteRow( row.getKey() );
            }

            Map<String, String> properties = metadataFacet.toProperties();

            for ( Map.Entry<String, String> entry : properties.entrySet() )
            {
                String key = new MetadataFacetModel.KeyBuilder().withKey( entry.getKey() ).withArtifactMetadataModel(
                    artifactMetadataModel ).withFacetId( facetId ).withName( metadataFacet.getName() ).build();
                Mutator<String> mutator = metadataFacetTemplate.createMutator() //
                    .addInsertion( key, cf, column( "repositoryName", artifactMetadataModel.getRepositoryId() ) ) //
                    .addInsertion( key, cf, column( "namespaceId", artifactMetadataModel.getNamespace() ) ) //
                    .addInsertion( key, cf, column( "projectId", artifactMetadataModel.getProject() ) ) //
                    .addInsertion( key, cf, column( "projectVersion", artifactMetadataModel.getProjectVersion() ) ) //
                    .addInsertion( key, cf, column( "facetId", facetId ) ) //
                    .addInsertion( key, cf, column( "key", entry.getKey() ) ) //
                    .addInsertion( key, cf, column( "value", entry.getValue() ) );

                if ( metadataFacet.getName() != null )
                {
                    mutator.addInsertion( key, cf, column( "name", metadataFacet.getName() ) );
                }

                mutator.execute();
            }
        }
    }


    @Override
    public List<String> getMetadataFacets( final String repositoryId, final String facetId )
        throws MetadataRepositoryException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "name" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "facetId", facetId ) //
            .execute();

        final List<String> facets = new ArrayList<>();

        for ( Row<String, String, String> row : result.get() )
        {
            facets.add( getStringValue( row.getColumnSlice(), "name" ) );
        }
        return facets;
    }

    @Override
    public boolean hasMetadataFacet( String repositoryId, String facetId )
        throws MetadataRepositoryException
    {
        return !getMetadataFacets( repositoryId, facetId ).isEmpty();
    }

    @Override
    public MetadataFacet getMetadataFacet( final String repositoryId, final String facetId, final String name )
        throws MetadataRepositoryException
    {

        MetadataFacetFactory metadataFacetFactory = metadataFacetFactories.get( facetId );
        if ( metadataFacetFactory == null )
        {
            return null;
        }

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "key", "value" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "facetId", facetId ) //
            .addEqualsExpression( "name", name ) //
            .execute();

        MetadataFacet metadataFacet = metadataFacetFactory.createMetadataFacet( repositoryId, name );
        int size = result.get().getCount();
        if ( size < 1 )
        {
            return null;
        }
        Map<String, String> map = new HashMap<>( size );
        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();
            map.put( getStringValue( columnSlice, "key" ), getStringValue( columnSlice, "value" ) );
        }
        metadataFacet.fromProperties( map );
        return metadataFacet;
    }

    @Override
    public void addMetadataFacet( String repositoryId, MetadataFacet metadataFacet )
        throws MetadataRepositoryException
    {

        if ( metadataFacet == null )
        {
            return;
        }

        if ( metadataFacet.toProperties().isEmpty() )
        {
            String key = new MetadataFacetModel.KeyBuilder().withRepositoryId( repositoryId ).withFacetId(
                metadataFacet.getFacetId() ).withName( metadataFacet.getName() ).build();

            boolean exists = this.metadataFacetTemplate.isColumnsExist( key );

            if ( exists )
            {
                ColumnFamilyUpdater<String, String> updater = this.metadataFacetTemplate.createUpdater( key );
                addUpdateStringValue( updater, "facetId", metadataFacet.getFacetId() );
                addUpdateStringValue( updater, "name", metadataFacet.getName() );
                this.metadataFacetTemplate.update( updater );
            }
            else
            {
                String cf = this.cassandraArchivaManager.getMetadataFacetFamilyName();
                this.metadataFacetTemplate.createMutator() //
                    .addInsertion( key, cf, column( "repositoryName", repositoryId ) ) //
                    .addInsertion( key, cf, column( "facetId", metadataFacet.getFacetId() ) ) //
                    .addInsertion( key, cf, column( "name", metadataFacet.getName() ) ) //
                    .execute();
            }

        }
        else
        {
            for ( Map.Entry<String, String> entry : metadataFacet.toProperties().entrySet() )
            {
                String key = new MetadataFacetModel.KeyBuilder().withRepositoryId( repositoryId ).withFacetId(
                    metadataFacet.getFacetId() ).withName( metadataFacet.getName() ).withKey( entry.getKey() ).build();

                boolean exists = this.metadataFacetTemplate.isColumnsExist( key );
                if ( !exists )
                {
                    String cf = this.cassandraArchivaManager.getMetadataFacetFamilyName();
                    this.metadataFacetTemplate.createMutator() //
                        .addInsertion( key, cf, column( "repositoryName", repositoryId ) ) //
                        .addInsertion( key, cf, column( "facetId", metadataFacet.getFacetId() ) ) //
                        .addInsertion( key, cf, column( "name", metadataFacet.getName() ) ) //
                        .addInsertion( key, cf, column( "key", entry.getKey() ) ) //
                        .addInsertion( key, cf, column( "value", entry.getValue() ) ) //
                        .execute();
                }
                else
                {
                    ColumnFamilyUpdater<String, String> updater = this.metadataFacetTemplate.createUpdater( key );
                    addUpdateStringValue( updater, "value", entry.getValue() );
                    this.metadataFacetTemplate.update( updater );
                }
            }
        }
    }

    @Override
    public void removeMetadataFacets( final String repositoryId, final String facetId )
        throws MetadataRepositoryException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "key", "value" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "facetId", facetId ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.metadataFacetTemplate.deleteRow( row.getKey() );
        }

    }

    @Override
    public void removeMetadataFacet( final String repositoryId, final String facetId, final String name )
        throws MetadataRepositoryException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "key", "value" ) //
            .addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "facetId", facetId ) //
            .addEqualsExpression( "name", name ) //
            .execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.metadataFacetTemplate.deleteRow( row.getKey() );
        }
    }

    @Override
    public List<ArtifactMetadata> getArtifactsByDateRange( final String repositoryId, final Date startTime,
                                                           final Date endTime )
        throws MetadataRepositoryException
    {

        LongSerializer ls = LongSerializer.get();
        RangeSlicesQuery<String, String, Long> query = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ls ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "namespaceId", "size", "id", "fileLastModified", "md5", "project", "projectVersion",
                             "repositoryName", "version", "whenGathered", "sha1" ); //

        if ( startTime != null )
        {
            query = query.addGteExpression( "whenGathered", startTime.getTime() );
        }
        if ( endTime != null )
        {
            query = query.addLteExpression( "whenGathered", endTime.getTime() );
        }
        QueryResult<OrderedRows<String, String, Long>> result = query.execute();

        List<ArtifactMetadata> artifactMetadatas = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, Long> row : result.get() )
        {
            ColumnSlice<String, Long> columnSlice = row.getColumnSlice();
            String repositoryName = getAsStringValue( columnSlice, "repositoryName" );
            if ( StringUtils.equals( repositoryName, repositoryId ) )
            {

                artifactMetadatas.add( mapArtifactMetadataLongColumnSlice( columnSlice ) );
            }
        }

        return artifactMetadatas;
    }


    protected ArtifactMetadata mapArtifactMetadataLongColumnSlice( ColumnSlice<String, Long> columnSlice )
    {
        ArtifactMetadata artifactMetadata = new ArtifactMetadata();
        artifactMetadata.setNamespace( getAsStringValue( columnSlice, "namespaceId" ) );
        artifactMetadata.setSize( getLongValue( columnSlice, "size" ) );
        artifactMetadata.setId( getAsStringValue( columnSlice, "id" ) );
        artifactMetadata.setFileLastModified( getLongValue( columnSlice, "fileLastModified" ) );
        artifactMetadata.setMd5( getAsStringValue( columnSlice, "md5" ) );
        artifactMetadata.setProject( getAsStringValue( columnSlice, "project" ) );
        artifactMetadata.setProjectVersion( getAsStringValue( columnSlice, "projectVersion" ) );
        artifactMetadata.setRepositoryId( getAsStringValue( columnSlice, "repositoryName" ) );
        artifactMetadata.setSha1( getAsStringValue( columnSlice, "sha1" ) );
        artifactMetadata.setVersion( getAsStringValue( columnSlice, "version" ) );
        Long whenGathered = getLongValue( columnSlice, "whenGathered" );
        if ( whenGathered != null )
        {
            artifactMetadata.setWhenGathered( new Date( whenGathered ) );
        }
        return artifactMetadata;
    }

    protected ArtifactMetadata mapArtifactMetadataStringColumnSlice( ColumnSlice<String, String> columnSlice )
    {
        ArtifactMetadata artifactMetadata = new ArtifactMetadata();
        artifactMetadata.setNamespace( getStringValue( columnSlice, "namespaceId" ) );
        artifactMetadata.setSize( getAsLongValue( columnSlice, "size" ) );
        artifactMetadata.setId( getStringValue( columnSlice, "id" ) );
        artifactMetadata.setFileLastModified( getAsLongValue( columnSlice, "fileLastModified" ) );
        artifactMetadata.setMd5( getStringValue( columnSlice, "md5" ) );
        artifactMetadata.setProject( getStringValue( columnSlice, "project" ) );
        artifactMetadata.setProjectVersion( getStringValue( columnSlice, "projectVersion" ) );
        artifactMetadata.setRepositoryId( getStringValue( columnSlice, "repositoryName" ) );
        artifactMetadata.setSha1( getStringValue( columnSlice, "sha1" ) );
        artifactMetadata.setVersion( getStringValue( columnSlice, "version" ) );
        Long whenGathered = getAsLongValue( columnSlice, "whenGathered" );
        if ( whenGathered != null )
        {
            artifactMetadata.setWhenGathered( new Date( whenGathered ) );
        }
        return artifactMetadata;
    }

    @Override
    public Collection<ArtifactMetadata> getArtifactsByChecksum( final String repositoryId, final String checksum )
        throws MetadataRepositoryException
    {

        // cql cannot run or in queries so running twice the query
        Map<String, ArtifactMetadata> artifactMetadataMap = new HashMap<>();

        RangeSlicesQuery<String, String, String> query = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "namespaceId", "size", "id", "fileLastModified", "md5", "project", "projectVersion",
                             "repositoryName", "version", "whenGathered", "sha1" ); //

        query = query.addEqualsExpression( "sha1", checksum ).addEqualsExpression( "repositoryName", repositoryId );

        QueryResult<OrderedRows<String, String, String>> result = query.execute();

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();

            artifactMetadataMap.put( row.getKey(), mapArtifactMetadataStringColumnSlice( columnSlice ) );

        }

        query = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "namespaceId", "size", "id", "fileLastModified", "md5", "project", "projectVersion",
                             "repositoryName", "version", "whenGathered", "sha1" ); //

        query = query.addEqualsExpression( "md5", checksum ).addEqualsExpression( "repositoryName", repositoryId );

        result = query.execute();

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();

            artifactMetadataMap.put( row.getKey(), mapArtifactMetadataStringColumnSlice( columnSlice ) );

        }

        return artifactMetadataMap.values();
    }


    @Override
    public void removeArtifact( final String repositoryId, final String namespace, final String project,
                                final String version, final String id )
        throws MetadataRepositoryException
    {
        logger.debug( "removeArtifact repositoryId: '{}', namespace: '{}', project: '{}', version: '{}', id: '{}'",
                      repositoryId, namespace, project, version, id );
        String key =
            new ArtifactMetadataModel.KeyBuilder().withRepositoryId( repositoryId ).withNamespace( namespace ).withId(
                id ).withProjectVersion( version ).withProject( project ).build();

        this.artifactMetadataTemplate.deleteRow( key );

        key = new ProjectVersionMetadataModel.KeyBuilder() //
            .withRepository( repositoryId ) //
            .withNamespace( namespace ) //
            .withProjectId( project ) //
            .withProjectVersion( version ) //
            .withId( id ) //
            .build();

        this.projectVersionMetadataTemplate.deleteRow( key );
    }

    @Override
    public void removeArtifact( ArtifactMetadata artifactMetadata, String baseVersion )
        throws MetadataRepositoryException
    {
        logger.debug( "removeArtifact repositoryId: '{}', namespace: '{}', project: '{}', version: '{}', id: '{}'",
                      artifactMetadata.getRepositoryId(), artifactMetadata.getNamespace(),
                      artifactMetadata.getProject(), baseVersion, artifactMetadata.getId() );
        String key =
            new ArtifactMetadataModel.KeyBuilder().withRepositoryId( artifactMetadata.getRepositoryId() ).withNamespace(
                artifactMetadata.getNamespace() ).withId( artifactMetadata.getId() ).withProjectVersion(
                baseVersion ).withProject( artifactMetadata.getProject() ).build();

        this.artifactMetadataTemplate.deleteRow( key );

    }

    @Override
    public void removeArtifact( final String repositoryId, final String namespace, final String project,
                                final String version, final MetadataFacet metadataFacet )
        throws MetadataRepositoryException
    {

        RangeSlicesQuery<String, String, String> query = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "namespaceId" ); //

        query = query.addEqualsExpression( "repositoryName", repositoryId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "project", project ) //
            .addEqualsExpression( "version", version );

        QueryResult<OrderedRows<String, String, String>> result = query.execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.artifactMetadataTemplate.deleteRow( row.getKey() );
        }
    }


    @Override
    public List<ArtifactMetadata> getArtifacts( final String repositoryId )
        throws MetadataRepositoryException
    {

        RangeSlicesQuery<String, String, String> query = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "namespaceId", "size", "id", "fileLastModified", "md5", "project", "projectVersion",
                             "repositoryName", "version", "whenGathered", "sha1" ); //

        query = query.addEqualsExpression( "repositoryName", repositoryId );

        QueryResult<OrderedRows<String, String, String>> result = query.execute();

        List<ArtifactMetadata> artifactMetadatas = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();

            artifactMetadatas.add( mapArtifactMetadataStringColumnSlice( columnSlice ) );

        }

        return artifactMetadatas;
    }


    @Override
    public Collection<ProjectVersionReference> getProjectReferences( String repoId, String namespace, String projectId,
                                                                     String projectVersion )
        throws MetadataResolutionException
    {
        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getDependencyFamilyName() ) //
            .setColumnNames( "projectVersionMetadataModel.key" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "groupId", namespace ) //
            .addEqualsExpression( "artifactId", projectId ) //
            .addEqualsExpression( "version", projectVersion ) //
            .execute();

        List<String> dependenciesIds = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get().getList() )
        {
            dependenciesIds.add( getStringValue( row.getColumnSlice(), "projectVersionMetadataModel.key" ) );
        }

        List<ProjectVersionReference> references = new ArrayList<>( result.get().getCount() );

        for ( String key : dependenciesIds )
        {
            ColumnFamilyResult<String, String> columnFamilyResult =
                this.projectVersionMetadataTemplate.queryColumns( key );
            references.add( new ProjectVersionReference( ProjectVersionReference.ReferenceType.DEPENDENCY, //
                                                         columnFamilyResult.getString( "projectId" ), //
                                                         columnFamilyResult.getString( "namespaceId" ), //
                                                         columnFamilyResult.getString( "projectVersion" ) ) );
        }

        return references;
    }

    @Override
    public void removeProjectVersion( final String repoId, final String namespace, final String projectId,
                                      final String projectVersion )
        throws MetadataRepositoryException
    {

        QueryResult<OrderedRows<String, String, String>> result = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getProjectVersionMetadataFamilyName() ) //
            .setColumnNames( "version" ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion ) //
            .execute();

        for ( Row<String, String, String> row : result.get().getList() )
        {
            this.projectVersionMetadataTemplate.deleteRow( row.getKey() );
            removeMailingList( row.getKey() );
            removeLicenses( row.getKey() );
            removeDependencies( row.getKey() );
        }

        RangeSlicesQuery<String, String, String> query = HFactory //
            .createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
            .setColumnNames( "namespaceId" ); //

        query = query.addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "project", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion );

        result = query.execute();

        for ( Row<String, String, String> row : result.get() )
        {
            this.artifactMetadataTemplate.deleteRow( row.getKey() );

        }
    }

    @Override
    public Collection<ArtifactMetadata> getArtifacts( final String repoId, final String namespace,
                                                      final String projectId, final String projectVersion )
        throws MetadataResolutionException
    {

        QueryResult<OrderedRows<String, String, String>> result =
            HFactory.createRangeSlicesQuery( keyspace, ss, ss, ss ) //
                .setColumnFamily( cassandraArchivaManager.getArtifactMetadataFamilyName() ) //
                .setColumnNames( "id", "repositoryName", "namespaceId", "project", "projectVersion", "version",
                                 "fileLastModified", "size", "md5", "sha1", "whenGathered" )//
                .setRowCount( Integer.MAX_VALUE ) //
                .addEqualsExpression( "repositoryName", repoId ) //
                .addEqualsExpression( "namespaceId", namespace ) //
                .addEqualsExpression( "project", projectId ) //
                .addEqualsExpression( "projectVersion", projectVersion ) //
                .execute();

        if ( result.get() == null || result.get().getCount() < 1 )
        {
            return Collections.emptyList();
        }

        List<ArtifactMetadata> artifactMetadatas = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();
            ArtifactMetadata artifactMetadata = new ArtifactMetadata();
            artifactMetadata.setNamespace( getStringValue( columnSlice, "namespaceId" ) );
            artifactMetadata.setSize( getAsLongValue( columnSlice, "size" ) );
            artifactMetadata.setId( getStringValue( columnSlice, "id" ) );
            artifactMetadata.setFileLastModified( getAsLongValue( columnSlice, "fileLastModified" ) );
            artifactMetadata.setMd5( getStringValue( columnSlice, "md5" ) );
            artifactMetadata.setProject( getStringValue( columnSlice, "project" ) );
            artifactMetadata.setProjectVersion( getStringValue( columnSlice, "projectVersion" ) );
            artifactMetadata.setRepositoryId( repoId );
            artifactMetadata.setSha1( getStringValue( columnSlice, "sha1" ) );
            artifactMetadata.setVersion( getStringValue( columnSlice, "version" ) );
            Long whenGathered = getAsLongValue( columnSlice, "whenGathered" );
            if ( whenGathered != null )
            {
                artifactMetadata.setWhenGathered( new Date( whenGathered ) );
            }
            artifactMetadatas.add( artifactMetadata );
        }

        result = HFactory.createRangeSlicesQuery( keyspace, ss, ss, ss ) //
            .setColumnFamily( cassandraArchivaManager.getMetadataFacetFamilyName() ) //
            .setColumnNames( "facetId", "name", "value", "key", "projectVersion" ) //
            .setRowCount( Integer.MAX_VALUE ) //
            .addEqualsExpression( "repositoryName", repoId ) //
            .addEqualsExpression( "namespaceId", namespace ) //
            .addEqualsExpression( "projectId", projectId ) //
            .addEqualsExpression( "projectVersion", projectVersion ) //
            .execute();

        if ( result.get() == null || result.get().getCount() < 1 )
        {
            return artifactMetadatas;
        }

        final List<MetadataFacetModel> metadataFacetModels = new ArrayList<>( result.get().getCount() );

        for ( Row<String, String, String> row : result.get() )
        {
            ColumnSlice<String, String> columnSlice = row.getColumnSlice();
            MetadataFacetModel metadataFacetModel = new MetadataFacetModel();
            metadataFacetModel.setFacetId( getStringValue( columnSlice, "facetId" ) );
            metadataFacetModel.setName( getStringValue( columnSlice, "name" ) );
            metadataFacetModel.setValue( getStringValue( columnSlice, "value" ) );
            metadataFacetModel.setKey( getStringValue( columnSlice, "key" ) );
            metadataFacetModel.setProjectVersion( getStringValue( columnSlice, "projectVersion" ) );
            metadataFacetModels.add( metadataFacetModel );
        }

        // rebuild MetadataFacet for artifacts

        for ( final ArtifactMetadata artifactMetadata : artifactMetadatas )
        {
            Iterable<MetadataFacetModel> metadataFacetModelIterable =
                Iterables.filter( metadataFacetModels, new Predicate<MetadataFacetModel>()
                {
                    @Override
                    public boolean apply( MetadataFacetModel metadataFacetModel )
                    {
                        if ( metadataFacetModel != null )
                        {
                            return StringUtils.equals( artifactMetadata.getVersion(),
                                                       metadataFacetModel.getProjectVersion() );
                        }
                        return false;
                    }
                } );
            Iterator<MetadataFacetModel> iterator = metadataFacetModelIterable.iterator();
            Map<String, List<MetadataFacetModel>> metadataFacetValuesPerFacetId = new HashMap<>();
            while ( iterator.hasNext() )
            {
                MetadataFacetModel metadataFacetModel = iterator.next();
                List<MetadataFacetModel> values = metadataFacetValuesPerFacetId.get( metadataFacetModel.getName() );
                if ( values == null )
                {
                    values = new ArrayList<>();
                    metadataFacetValuesPerFacetId.put( metadataFacetModel.getFacetId(), values );
                }
                values.add( metadataFacetModel );

            }

            for ( Map.Entry<String, List<MetadataFacetModel>> entry : metadataFacetValuesPerFacetId.entrySet() )
            {
                MetadataFacetFactory metadataFacetFactory = metadataFacetFactories.get( entry.getKey() );
                if ( metadataFacetFactory != null )
                {
                    List<MetadataFacetModel> facetModels = entry.getValue();
                    if ( !facetModels.isEmpty() )
                    {
                        MetadataFacet metadataFacet = metadataFacetFactory.createMetadataFacet();
                        Map<String, String> props = new HashMap<>( facetModels.size() );
                        for ( MetadataFacetModel metadataFacetModel : facetModels )
                        {
                            props.put( metadataFacetModel.getKey(), metadataFacetModel.getValue() );
                        }
                        metadataFacet.fromProperties( props );
                        artifactMetadata.addFacet( metadataFacet );
                    }
                }
            }


        }

        return artifactMetadatas;
    }

    @Override
    public void save()
    {
        logger.trace( "save" );
    }

    @Override
    public void close()
        throws MetadataRepositoryException
    {
        logger.trace( "close" );
    }

    @Override
    public void revert()
    {
        logger.warn( "CassandraMetadataRepository cannot revert" );
    }

    @Override
    public boolean canObtainAccess( Class<?> aClass )
    {
        return false;
    }

    @Override
    public <T> T obtainAccess( Class<T> aClass )
        throws MetadataRepositoryException
    {
        throw new IllegalArgumentException(
            "Access using " + aClass + " is not supported on the cassandra metadata storage" );
    }


    private static class ModelMapperHolder
    {
        private static ModelMapper MODEL_MAPPER = new ModelMapper();
    }

    protected ModelMapper getModelMapper()
    {
        return ModelMapperHolder.MODEL_MAPPER;
    }
}
