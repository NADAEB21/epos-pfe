import { TestBed } from '@angular/core/testing';

import { ExamenAdminService } from './examen-admin.service';

describe('ExamenAdminService', () => {
  let service: ExamenAdminService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ExamenAdminService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
