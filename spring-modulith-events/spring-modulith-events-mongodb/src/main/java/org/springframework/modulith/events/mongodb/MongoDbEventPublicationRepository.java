/*
 * Copyright 2022-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.modulith.events.mongodb;

import static org.springframework.data.mongodb.core.query.Criteria.*;
import static org.springframework.data.mongodb.core.query.Query.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.util.TypeInformation;
import org.springframework.modulith.events.CompletableEventPublication;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.EventPublicationRepository;
import org.springframework.modulith.events.PublicationTargetIdentifier;
import org.springframework.util.Assert;

/**
 * Repository to store {@link EventPublication}s in a MongoDB.
 *
 * @author Björn Kieling
 * @author Dmitry Belyaev
 * @author Oliver Drotbohm
 * @author Takeshi Ogawa
 */
class MongoDbEventPublicationRepository implements EventPublicationRepository {

	private final MongoTemplate mongoTemplate;

	/**
	 * Creates a new {@link MongoDbEventPublicationRepository} for the given {@link MongoTemplate}.
	 *
	 * @param mongoTemplate must not be {@literal null}.
	 */
	public MongoDbEventPublicationRepository(MongoTemplate mongoTemplate) {

		Assert.notNull(mongoTemplate, "MongoTemplate must not be null!");

		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public EventPublication create(EventPublication publication) {

		mongoTemplate.save(domainToDocument(publication));

		return publication;
	}

	@Override
	public EventPublication update(CompletableEventPublication publication) {

		return findDocumentsByEventAndTargetIdentifierAndCompletionDateNull(publication.getEvent(),
				publication.getTargetIdentifier()) //
						.stream() //
						.findFirst() //
						.map(document -> document.markCompleted(publication.getCompletionDate().orElse(null))) //
						.map(mongoTemplate::save) //
						.map(this::documentToDomain) //
						.orElse(publication);
	}

	@Override
	public List<EventPublication> findIncompletePublications() {

		var query = query(where("completionDate").isNull()).with(Sort.by("publicationDate").ascending());

		return mongoTemplate.find(query, MongoDbEventPublication.class).stream() //
				.<EventPublication> map(this::documentToDomain) //
				.toList();
	}

	@Override
	public Optional<EventPublication> findIncompletePublicationsByEventAndTargetIdentifier(
			Object event, PublicationTargetIdentifier targetIdentifier) {

		var documents = findDocumentsByEventAndTargetIdentifierAndCompletionDateNull(event, targetIdentifier);
		var results = documents
				.stream() //
				.map(this::documentToDomain) //
				.toList();

		// if there are several events with exactly the same payload we return the oldest one first
		return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
	}

	@Override
	public void deleteCompletedPublications() {
		mongoTemplate.remove(query(where("completionDate").ne(null)), MongoDbEventPublication.class);
	}

	private List<MongoDbEventPublication> findDocumentsByEventAndTargetIdentifierAndCompletionDateNull( //
			Object event, PublicationTargetIdentifier targetIdentifier) {

		// we need to enforce writing of the type information
		var eventAsMongoType = mongoTemplate.getConverter().convertToMongoType(event, TypeInformation.OBJECT);

		var query = query(
				where("event").is(eventAsMongoType) //
						.and("listenerId").is(targetIdentifier.getValue()) //
						.and("completionDate").isNull()) //
								.with(Sort.by("publicationDate").ascending());

		return mongoTemplate.find(query, MongoDbEventPublication.class);
	}

	private MongoDbEventPublication domainToDocument(EventPublication publication) {

		return new MongoDbEventPublication( //
				publication.getPublicationDate(), //
				publication.getTargetIdentifier().getValue(), //
				publication.getEvent());
	}

	private CompletableEventPublication documentToDomain(MongoDbEventPublication document) {
		return new MongoDbEventPublicationAdapter(document);
	}

	private static class MongoDbEventPublicationAdapter implements CompletableEventPublication {

		private final MongoDbEventPublication publication;

		MongoDbEventPublicationAdapter(MongoDbEventPublication publication) {
			this.publication = publication;
		}

		@Override
		public Object getEvent() {
			return publication.event;
		}

		@Override
		public PublicationTargetIdentifier getTargetIdentifier() {
			return PublicationTargetIdentifier.of(publication.listenerId);
		}

		@Override
		public Instant getPublicationDate() {
			return publication.publicationDate;
		}

		@Override
		public Optional<Instant> getCompletionDate() {
			return Optional.ofNullable(publication.completionDate);
		}

		@Override
		public boolean isPublicationCompleted() {
			return publication.completionDate != null;
		}

		@Override
		public CompletableEventPublication markCompleted() {
			publication.completionDate = Instant.now();
			return this;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {

			if (this == obj) {
				return true;
			}

			if (!(obj instanceof MongoDbEventPublicationAdapter other)) {
				return false;
			}

			return Objects.equals(publication, other.publication);
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			return Objects.hash(publication);
		}
	}
}
