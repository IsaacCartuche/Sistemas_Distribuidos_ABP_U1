import axios from 'axios';

jest.mock('axios');

describe('apiService', () => {
  it('debería existir el módulo api', () => {
    expect(axios).toBeDefined();
  });
});
