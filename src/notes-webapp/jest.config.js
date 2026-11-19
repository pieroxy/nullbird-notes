/** Tests live in src/test/ts, compiled by ts-jest with tsconfig.test.json (CommonJS, Jest's types). */
module.exports = {
  testEnvironment: 'node',
  roots: ['<rootDir>/src/test/ts'],
  moduleFileExtensions: ['ts', 'js'],
  transform: {
    '^.+\\.ts$': ['ts-jest', { tsconfig: 'tsconfig.test.json' }],
  },
};
