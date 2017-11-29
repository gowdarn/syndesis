import { Injectable } from '@angular/core';
import { ApiConnectorService } from './api-connector.service';
import { ApiConnector, ApiConnectors, TypeFactory } from './api-connector.model';
import { AbstractStore } from '../../store/entity/entity.store';
import { EventsService } from '../../store/entity/events.service';

@Injectable()
export class ApiConnectorStore extends AbstractStore<
  ApiConnector,
  ApiConnectors,
  ApiConnectorService
  > {
  constructor(apiConnectorService: ApiConnectorService, eventService: EventsService) {
    super(apiConnectorService, eventService, [], TypeFactory.createApiConnector());
  }

  protected get kind() {
    return 'ApiConnector';
  }

}
