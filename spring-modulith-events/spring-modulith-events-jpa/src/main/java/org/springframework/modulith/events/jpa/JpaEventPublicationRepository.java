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
package org.springframework.modulith.events.jpa;

import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.modulith.events.CompletableEventPublication;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.EventPublicationRepository;
import org.springframework.modulith.events.EventSerializer;
import org.springframework.modulith.events.PublicationTargetIdentifier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Repository to store {@link EventPublication}s.
 *
 * @author Oliver Drotbohm
 * @author Dmitry Belyaev
 * @author Björn Kieling
 * @author Takeshi Ogawa
 */
class JpaEventPublicationRepository implements EventPublicationRepository {

	private static String BY_EVENT_AND_LISTENER_ID = """
			select p
			from JpaEventPublication p
				where
					p.serializedEvent = ?1
					and p.listenerId = ?2
					and p.completionDate is null
			""";

	private static String INCOMPLETE = """
			select p
			from JpaEventPublication p
			where
				p.completionDate is null
			order by
				p.publicationDate asc
			""";

	private static final String DELETE_COMPLETED = """
			delete
			from JpaEventPublication p
			where
				p.completionDate is not null
			""";

	private final EntityManager entityManager;
	private final EventSerializer serializer;

	/**
	 * Creates a new {@link JpaEventPublicationRepository} for the given {@link EntityManager} and
	 * {@link EventSerializer}.
	 *
	 * @param entityManager must not be {@literal null}.
	 * @param serializer must not be {@literal null}.
	 */
	public JpaEventPublicationRepository(EntityManager entityManager, EventSerializer serializer) {

		Assert.notNull(entityManager, "EntityManager must not be null!");
		Assert.notNull(serializer, "EventSerializer must not be null!");

		this.entityManager = entityManager;
		this.serializer = serializer;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.modulith.events.EventPublicationRepository#create(org.springframework.modulith.events.EventPublication)
	 */
	@Override
	@Transactional
	public EventPublication create(EventPublication publication) {

		entityManager.persist(domainToEntity(publication));

		return publication;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.modulith.events.EventPublicationRepository#update(org.springframework.modulith.events.CompletableEventPublication)
	 */
	@Override
	@Transactional
	public EventPublication update(CompletableEventPublication publication) {

		var id = publication.getTargetIdentifier().getValue();
		var event = publication.getEvent();

		findEntityBySerializedEventAndListenerIdAndCompletionDateNull(event, id) //
				.ifPresent(entity -> entity.completionDate = publication.getCompletionDate().orElse(null));

		return publication;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.modulith.events.EventPublicationRepository#findIncompletePublications()
	 */
	@Override
	@Transactional(readOnly = true)
	public List<EventPublication> findIncompletePublications() {

		return entityManager.createQuery(INCOMPLETE, JpaEventPublication.class)
				.getResultStream()
				.map(this::entityToDomain)
				.toList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.modulith.events.EventPublicationRepository#findIncompletePublicationsByEventAndTargetIdentifier(java.lang.Object, org.springframework.modulith.events.PublicationTargetIdentifier)
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<EventPublication> findIncompletePublicationsByEventAndTargetIdentifier( //
			Object event, PublicationTargetIdentifier targetIdentifier) {

		return findEntityBySerializedEventAndListenerIdAndCompletionDateNull(event, targetIdentifier.getValue())
				.map(this::entityToDomain);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.modulith.events.EventPublicationRepository#deleteCompletedPublications()
	 */
	@Override
	@Transactional
	public void deleteCompletedPublications() {
		entityManager.createQuery(DELETE_COMPLETED).executeUpdate();
	}

	private Optional<JpaEventPublication> findEntityBySerializedEventAndListenerIdAndCompletionDateNull( //
			Object event, String listenerId) {

		var serializedEvent = serializeEvent(event);

		var query = entityManager.createQuery(BY_EVENT_AND_LISTENER_ID, JpaEventPublication.class)
				.setParameter(1, serializedEvent)
				.setParameter(2, listenerId);

		return query.getResultStream().findFirst();
	}

	private String serializeEvent(Object event) {
		return serializer.serialize(event).toString();
	}

	private JpaEventPublication domainToEntity(EventPublication domain) {
		return new JpaEventPublication(domain.getPublicationDate(), domain.getTargetIdentifier().getValue(),
				serializeEvent(domain.getEvent()), domain.getEvent().getClass());
	}

	private EventPublication entityToDomain(JpaEventPublication entity) {
		return new JpaEventPublicationAdapter(entity, serializer);
	}

	private static class JpaEventPublicationAdapter implements CompletableEventPublication {

		private final JpaEventPublication publication;
		private final EventSerializer serializer;

		/**
		 * Creates a new {@link JpaEventPublicationAdapter} for the given {@link JpaEventPublication} and
		 * {@link EventSerializer}.
		 *
		 * @param publication must not be {@literal null}.
		 * @param serializer must not be {@literal null}.
		 */
		public JpaEventPublicationAdapter(JpaEventPublication publication, EventSerializer serializer) {

			Assert.notNull(publication, "JpaEventPublication must not be null!");
			Assert.notNull(serializer, "EventSerializer must not be null!");

			this.publication = publication;
			this.serializer = serializer;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.modulith.events.EventPublication#getEvent()
		 */
		@Override
		public Object getEvent() {
			return serializer.deserialize(publication.serializedEvent, publication.eventType);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.modulith.events.EventPublication#getTargetIdentifier()
		 */
		@Override
		public PublicationTargetIdentifier getTargetIdentifier() {
			return PublicationTargetIdentifier.of(publication.listenerId);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.modulith.events.EventPublication#getPublicationDate()
		 */
		@Override
		public Instant getPublicationDate() {
			return publication.publicationDate;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.modulith.events.CompletableEventPublication#getCompletionDate()
		 */
		@Override
		public Optional<Instant> getCompletionDate() {
			return Optional.ofNullable(publication.completionDate);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.modulith.events.CompletableEventPublication#isPublicationCompleted()
		 */
		@Override
		public boolean isPublicationCompleted() {
			return publication.completionDate != null;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.modulith.events.CompletableEventPublication#markCompleted()
		 */
		@Override
		public CompletableEventPublication markCompleted() {
			publication.markCompleted();
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

			if (!(obj instanceof JpaEventPublicationAdapter that)) {
				return false;
			}

			return Objects.equals(publication, that.publication)
					&& Objects.equals(serializer, that.serializer);
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			return Objects.hash(publication, serializer);
		}
	}
}
